package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * File storage for a store whose values are maps, written one file per shard of each key. A
 * change to one map entry rewrites the one shard file that holds it, so a store holding one large
 * value and one small, busy value (a large DynamoDB table next to a heartbeat table) no longer
 * rewrites the large one for every change to the small one, as {@link PersistentStorage} and
 * {@link HybridStorage} do with their single file. With more than one shard, a change to a large
 * value rewrites only a fraction of it.
 *
 * <p>An entry's shard is {@code floorMod(entryKey.hashCode(), shards)}; {@link String#hashCode}
 * is fixed by the language, so it is stable across runs. Each file records its own key, so a file
 * name only has to be unique and safe: it is a readable prefix of the key, a hash of it and, with
 * more than one shard, the shard's number and the shard count. Files written under another shard
 * count are merged on load, rewritten under the current count and then removed.
 *
 * <p>A {@link Mode#SYNC} store writes a shard's file before the mutating call returns, like
 * {@link PersistentStorage}. A {@link Mode#PERIODIC} store marks the shard dirty and writes every
 * dirty shard on the flush interval, like {@link HybridStorage}. A {@link Mode#JOURNAL} store
 * appends one record per changed entry to {@value #JOURNAL_FILE} in the directory, like
 * {@link WalStorage}, but its compaction rewrites only the shards changed since the last one
 * instead of a snapshot of the whole store. Load replays the journal over the shard files.
 *
 * <p>On the first load of an empty directory, a legacy single-file store (the {@code Map<String,
 * Map<String, E>>} file the other backends write) is migrated: its keys are written as files and
 * the legacy file is renamed with a {@code .migrated} suffix so it is never read again.
 *
 * @param <E> the type of one map entry's value
 */
public class PartitionedFileStorage<E> implements StorageBackend<String, Map<String, E>>, MapEntryWriter<E> {

    private static final Logger LOG = Logger.getLogger(PartitionedFileStorage.class);

    static final String FILE_SUFFIX = ".json";
    static final String JOURNAL_FILE = "journal.wal";
    private static final String KEY_FIELD = "key";
    private static final String ENTRIES_FIELD = "entries";
    private static final int READABLE_PREFIX_LENGTH = 80;

    /** When a change reaches disk. */
    public enum Mode {
        /** Its shard file is rewritten before the mutating call returns. */
        SYNC,
        /** Dirty shard files are rewritten on the flush interval. */
        PERIODIC,
        /**
         * One record per changed entry is appended to a journal before the mutating call returns;
         * dirty shard files are rewritten and the journal emptied on the compaction interval.
         */
        JOURNAL
    }

    /** One shard of one key: the unit that is written to a file. */
    private record ShardId(String key, int shard) {}

    /** The value stored under one key, split into its shards. */
    private static final class Shards<E> {
        private final List<ConcurrentHashMap<String, E>> parts;

        Shards(int count) {
            List<ConcurrentHashMap<String, E>> created = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                created.add(new ConcurrentHashMap<>());
            }
            this.parts = List.copyOf(created);
        }

        ConcurrentHashMap<String, E> part(int shard) {
            return parts.get(shard);
        }

        Map<String, E> merged() {
            Map<String, E> merged = new HashMap<>();
            parts.forEach(merged::putAll);
            return Collections.unmodifiableMap(merged);
        }
    }

    private final ConcurrentHashMap<String, Shards<E>> store = new ConcurrentHashMap<>();
    private final Set<ShardId> dirty = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<ShardId, Object> fileLocks = new ConcurrentHashMap<>();
    private final Path directory;
    private final Path legacyFile;
    private final int shardCount;
    private final Mode mode;
    private final ObjectMapper mapper;
    private final ObjectReader entriesReader;
    private final ObjectWriter entriesWriter;
    private final JavaType legacyType;
    private final ScheduledExecutorService scheduler;
    private final EntryJournal<E> journal;
    // Journal mode: changes hold the read lock while they update memory and append their record,
    // and compaction holds the write lock while it writes dirty shards and truncates the journal,
    // so the journal never drops a record whose change is not yet in a shard file.
    private final ReentrantReadWriteLock compactionLock = new ReentrantReadWriteLock();
    // Runs inside compaction between the last shard write and emptying the journal: the moment a
    // change that slipped past the lock would be lost. Tests use it to stage that change.
    private volatile Runnable beforeJournalTruncate = () -> { };

    /**
     * @param directory       where the shard files live
     * @param legacyFile      a single-file store to migrate on first load, or {@code null}
     * @param entryType       the type of one map entry's value
     * @param shardCount      how many files each key is split into, at least one
     * @param mode            when a change reaches disk
     * @param flushIntervalMs the flush interval of a {@link Mode#PERIODIC} store, or the
     *                        compaction interval of a {@link Mode#JOURNAL} store
     */
    public PartitionedFileStorage(Path directory, Path legacyFile, Class<E> entryType, int shardCount,
                                  Mode mode, long flushIntervalMs) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1, was " + shardCount);
        }
        this.directory = directory;
        this.legacyFile = legacyFile;
        this.shardCount = shardCount;
        this.mode = mode;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        JavaType entriesType = mapper.getTypeFactory().constructMapType(Map.class, String.class, entryType);
        this.entriesReader = mapper.readerFor(entriesType);
        this.entriesWriter = mapper.writerFor(entriesType);
        this.legacyType = mapper.getTypeFactory().constructMapType(Map.class,
                mapper.getTypeFactory().constructType(String.class), entriesType);
        this.journal = mode == Mode.JOURNAL
                ? new EntryJournal<>(directory.resolve(JOURNAL_FILE), mapper.constructType(entryType), entriesType)
                : null;
        this.scheduler = mode == Mode.SYNC
                ? null
                : StorageFiles.scheduleEvery("partitioned-storage-flush", flushIntervalMs, this::flushQuietly);
    }

    // --- StorageBackend ---

    @Override
    public void put(String key, Map<String, E> value) {
        beginChange();
        try {
            applyPut(key, value);
            if (journal != null) {
                journal.put(key, value);
            }
        } finally {
            endChange();
        }
        writeAllNowIfSync(key);
    }

    @Override
    public void putAll(Map<String, Map<String, E>> entries) {
        entries.forEach(this::put);
    }

    /** A read-only copy of the stored map. */
    @Override
    public Optional<Map<String, E>> get(String key) {
        Shards<E> value = store.get(key);
        return value == null ? Optional.empty() : Optional.of(value.merged());
    }

    @Override
    public void delete(String key) {
        beginChange();
        try {
            applyDelete(key);
            if (journal != null) {
                journal.delete(key);
            }
        } finally {
            endChange();
        }
        writeAllNowIfSync(key);
    }

    @Override
    public List<Map<String, E>> scan(Predicate<String> keyFilter) {
        return store.entrySet().stream()
                .filter(e -> keyFilter.test(e.getKey()))
                .map(e -> e.getValue().merged())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }

    /** Writes every dirty shard; in journal mode this is a compaction. */
    @Override
    public void flush() {
        if (journal != null) {
            compact();
            return;
        }
        writeDirtyShards();
    }

    @Override
    public synchronized void load() {
        store.clear();
        dirty.clear();
        if (Files.isDirectory(directory)) {
            loadDirectory();
        }
        if (store.isEmpty() && legacyFile != null && Files.exists(legacyFile)) {
            migrateLegacyFile();
        }
        if (journal != null) {
            int replayed = journal.replay(this::applyReplayed);
            if (replayed > 0) {
                LOG.infov("Replayed {0} journal records into {1}", replayed, directory);
            }
            journal.open();
        }
    }

    @Override
    public synchronized void clear() {
        compactionLock.writeLock().lock();
        try {
            store.clear();
            dirty.clear();
            if (journal != null) {
                journal.deleteFile();
            }
            if (Files.isDirectory(directory)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
                    for (Path file : files) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    LOG.errorv(e, "Failed to clear {0}", directory);
                }
            }
            if (journal != null) {
                journal.open();
            }
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    // --- MapEntryWriter ---

    @Override
    public void putEntry(String key, String entryKey, E entryValue) {
        ShardId id;
        beginChange();
        try {
            id = applyPutEntry(key, entryKey, entryValue);
            if (journal != null) {
                journal.putEntry(key, entryKey, entryValue);
            }
        } finally {
            endChange();
        }
        writeNowIfSync(id);
    }

    @Override
    public void removeEntry(String key, String entryKey) {
        ShardId id;
        beginChange();
        try {
            id = applyRemoveEntry(key, entryKey);
            if (id != null && journal != null) {
                journal.removeEntry(key, entryKey);
            }
        } finally {
            endChange();
        }
        if (id != null) {
            writeNowIfSync(id);
        }
    }

    // ---

    /** Stops the flush schedule and writes what is still dirty; a journal is compacted and closed. */
    public void shutdown() {
        if (scheduler != null) {
            StorageFiles.stop(scheduler);
        }
        flush();
        if (journal != null) {
            journal.close();
        }
    }

    /**
     * Writes every dirty shard and then empties the journal. A shard that fails to write keeps
     * the journal intact, so the next load replays what that shard file is missing.
     */
    private void compact() {
        compactionLock.writeLock().lock();
        try {
            if (dirty.isEmpty() && journal.isEmpty()) {
                return;
            }
            writeDirtyShards();
            if (!dirty.isEmpty()) {
                LOG.errorv("Could not write {0} shards of {1}; keeping the journal", dirty.size(), directory);
                return;
            }
            beforeJournalTruncate.run();
            journal.truncate();
        } catch (IOException e) {
            LOG.errorv(e, "Failed to truncate the journal of {0}", directory);
        } finally {
            compactionLock.writeLock().unlock();
        }
    }

    private void applyReplayed(EntryJournal.Change<E> change) {
        switch (change) {
            case EntryJournal.PutEntry<E> put -> applyPutEntry(put.key(), put.entryKey(), put.value());
            case EntryJournal.RemoveEntry<E> remove -> applyRemoveEntry(remove.key(), remove.entryKey());
            case EntryJournal.Put<E> put -> applyPut(put.key(), put.value());
            case EntryJournal.Delete<E> delete -> applyDelete(delete.key());
        }
    }

    // The apply methods change memory and mark what they change dirty. Live changes call them
    // under the change lock and then journal the change; replay calls them for journal records.

    private ShardId applyPutEntry(String key, String entryKey, E entryValue) {
        ShardId id = new ShardId(key, shardOf(entryKey));
        store.computeIfAbsent(key, ignored -> new Shards<>(shardCount)).part(id.shard()).put(entryKey, entryValue);
        dirty.add(id);
        return id;
    }

    /** The shard the entry was removed from, or {@code null} when there was no such entry. */
    private ShardId applyRemoveEntry(String key, String entryKey) {
        ShardId id = new ShardId(key, shardOf(entryKey));
        Shards<E> value = store.get(key);
        if (value == null || value.part(id.shard()).remove(entryKey) == null) {
            return null;
        }
        dirty.add(id);
        return id;
    }

    private void applyPut(String key, Map<String, E> value) {
        store.put(key, split(value));
        markAll(key);
    }

    private void applyDelete(String key) {
        store.remove(key);
        markAll(key);
    }

    private void beginChange() {
        if (journal != null) {
            compactionLock.readLock().lock();
        }
    }

    private void endChange() {
        if (journal != null) {
            compactionLock.readLock().unlock();
        }
    }

    private void writeDirtyShards() {
        for (ShardId id : List.copyOf(dirty)) {
            writeShard(id);
        }
    }

    void setBeforeJournalTruncate(Runnable hook) {
        this.beforeJournalTruncate = hook;
    }

    /** The file that holds {@code entryKey} of {@code key}. */
    Path fileFor(String key, String entryKey) {
        return fileFor(new ShardId(key, shardOf(entryKey)));
    }

    /** The file of a single-shard store that holds {@code key}. */
    Path fileFor(String key) {
        return fileFor(new ShardId(key, 0));
    }

    int shardOf(String entryKey) {
        return Math.floorMod(entryKey.hashCode(), shardCount);
    }

    private Path fileFor(ShardId id) {
        return directory.resolve(fileNameFor(id.key(), id.shard(), shardCount));
    }

    static String fileNameFor(String key, int shard, int shardCount) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < key.length() && name.length() < READABLE_PREFIX_LENGTH; i++) {
            char c = key.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            name.append(safe ? c : '_');
        }
        name.append('-').append(shortHash(key));
        if (shardCount > 1) {
            name.append('.').append(shard).append("-of-").append(shardCount);
        }
        return name.append(FILE_SUFFIX).toString();
    }

    private static String shortHash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private Shards<E> split(Map<String, E> value) {
        Shards<E> shards = new Shards<>(shardCount);
        value.forEach((entryKey, entryValue) -> shards.part(shardOf(entryKey)).put(entryKey, entryValue));
        return shards;
    }

    private void markAll(String key) {
        for (int shard = 0; shard < shardCount; shard++) {
            dirty.add(new ShardId(key, shard));
        }
    }

    private void writeAllNowIfSync(String key) {
        if (mode == Mode.SYNC) {
            for (int shard = 0; shard < shardCount; shard++) {
                writeShard(new ShardId(key, shard));
            }
        }
    }

    private void writeNowIfSync(ShardId id) {
        if (mode == Mode.SYNC) {
            writeShard(id);
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            LOG.errorv(e, "Failed to flush {0}", directory);
        }
    }

    /**
     * Writes one shard's file, or deletes it when the shard is empty or its key is gone. The dirty
     * mark is cleared before the shard is read, so a change racing the write marks it again and
     * is written next.
     */
    private void writeShard(ShardId id) {
        Object lock = fileLocks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            if (!dirty.remove(id)) {
                return;
            }
            Path file = fileFor(id);
            try {
                Shards<E> value = store.get(id.key());
                ConcurrentHashMap<String, E> part = value == null ? null : value.part(id.shard());
                if (part == null || part.isEmpty()) {
                    Files.deleteIfExists(file);
                    return;
                }
                StorageFiles.writeAtomically(file, tempFile -> {
                    try (OutputStream out = Files.newOutputStream(tempFile);
                         JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
                        generator.writeStartObject();
                        generator.writeStringField(KEY_FIELD, id.key());
                        generator.writeFieldName(ENTRIES_FIELD);
                        entriesWriter.writeValue(generator, part);
                        generator.writeEndObject();
                    }
                });
            } catch (IOException e) {
                dirty.add(id);
                LOG.errorv(e, "Failed to persist shard {0} of {1} to {2}", id.shard(), id.key(), file);
            }
        }
    }

    private void loadDirectory() {
        List<Path> superseded = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                String key = readFile(file);
                if (key != null && !isCurrentFileOf(file, key)) {
                    superseded.add(file);
                    markAll(key);
                }
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to list {0}", directory);
        }
        LOG.infov("Loaded {0} keys from {1}", store.size(), directory);
        if (superseded.isEmpty()) {
            return;
        }
        // Written under another name or shard count: write each key under its current files
        // first, and only then drop the old ones, so a crash in between loses nothing.
        writeDirtyShards();
        if (!dirty.isEmpty()) {
            LOG.errorv("Could not rewrite {0} shards of {1}; keeping the superseded files", dirty.size(), directory);
            return;
        }
        for (Path file : superseded) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warnv(e, "Failed to remove superseded file {0}", file);
            }
        }
        LOG.infov("Rewrote {0} superseded files of {1} as {2} shards per key", superseded.size(), directory,
                shardCount);
    }

    private boolean isCurrentFileOf(Path file, String key) {
        for (int shard = 0; shard < shardCount; shard++) {
            if (file.equals(fileFor(new ShardId(key, shard)))) {
                return true;
            }
        }
        return false;
    }

    /** Merges one file into the store and returns its key, or {@code null} when it is unreadable. */
    private String readFile(Path file) {
        String key = null;
        Map<String, E> entries = null;
        try (JsonParser parser = mapper.getFactory().createParser(file.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("not a JSON object");
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case KEY_FIELD -> key = parser.getValueAsString();
                    case ENTRIES_FIELD -> entries = entriesReader.readValue(parser);
                    default -> parser.skipChildren();
                }
            }
            if (key == null || entries == null) {
                throw new IOException("missing \"" + KEY_FIELD + "\" or \"" + ENTRIES_FIELD + "\"");
            }
        } catch (IOException e) {
            StorageQuarantine.quarantine(file, e, LOG);
            return null;
        }
        Shards<E> shards = store.computeIfAbsent(key, ignored -> new Shards<>(shardCount));
        entries.forEach((entryKey, entryValue) -> shards.part(shardOf(entryKey)).put(entryKey, entryValue));
        return key;
    }

    private void migrateLegacyFile() {
        Map<String, Map<String, E>> legacy;
        try {
            legacy = mapper.readValue(legacyFile.toFile(), legacyType);
        } catch (IOException e) {
            StorageQuarantine.quarantine(legacyFile, e, LOG);
            return;
        }
        legacy.forEach((key, value) -> {
            store.put(key, split(value));
            markAll(key);
        });
        writeDirtyShards();
        if (!dirty.isEmpty()) {
            LOG.errorv("Migration of {0} into {1} is incomplete; keeping the legacy file", legacyFile, directory);
            return;
        }
        Path migrated = legacyFile.resolveSibling(legacyFile.getFileName() + ".migrated");
        try {
            Files.move(legacyFile, migrated, StandardCopyOption.REPLACE_EXISTING);
            LOG.infov("Migrated {0} keys from {1} into {2}; the old file is now {3}",
                    legacy.size(), legacyFile, directory, migrated);
        } catch (IOException e) {
            LOG.errorv(e, "Migrated {0} into {1} but could not rename it", legacyFile, directory);
        }
    }
}
