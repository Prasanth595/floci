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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import java.util.stream.Stream;

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
 * dirty shard on the flush interval, like {@link HybridStorage}.
 *
 * <p>While the directory holds no data, a legacy single-file store (the {@code Map<String,
 * Map<String, E>>} file the other backends write, or a {@code wal} snapshot and log) is migrated:
 * its keys are written to a staging directory that is moved in place in one step, and the legacy
 * files are then renamed with a {@code .migrated} suffix so they are never read again.
 *
 * @param <E> the type of one map entry's value
 */
public class PartitionedFileStorage<E> implements StorageBackend<String, Map<String, E>>, MapEntryWriter<E> {

    private static final Logger LOG = Logger.getLogger(PartitionedFileStorage.class);

    static final String FILE_SUFFIX = ".json";
    static final String MIGRATING_SUFFIX = ".migrating";
    private static final String KEY_FIELD = "key";
    private static final String ENTRIES_FIELD = "entries";
    private static final int READABLE_PREFIX_LENGTH = 80;

    /** When a change reaches disk. */
    public enum Mode {
        /** Its shard file is rewritten before the mutating call returns. */
        SYNC,
        /** Dirty shard files are rewritten on the flush interval. */
        PERIODIC
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
    private final LegacyStore<E> legacy;
    private final int shardCount;
    private final Mode mode;
    private final ObjectMapper mapper;
    private final ObjectReader entriesReader;
    private final ObjectWriter entriesWriter;
    private final ScheduledExecutorService scheduler;
    // Shard writes hold the read lock, and load and clear hold the write lock, so a write that
    // read a shard before a clear cannot put its file back afterwards, and the flush schedule,
    // which starts with the constructor, cannot write while load is filling the store.
    private final ReentrantReadWriteLock fileLock = new ReentrantReadWriteLock();
    // Runs inside a shard write after the shard was read and before its file is replaced: the
    // moment a clear() that slipped past the lock would be undone. Tests use it to stage that.
    private volatile Runnable beforeShardWrite = () -> { };
    // Runs on load after superseded files were rewritten under their current names and before
    // the old files are removed. Tests use it to make only the removal fail.
    private volatile Runnable beforeSupersededDelete = () -> { };

    /**
     * @param directory       where the shard files live
     * @param legacyFile      a plain single-file store to migrate on first load, or {@code null}
     * @param entryType       the type of one map entry's value
     * @param shardCount      how many files each key is split into, at least one
     * @param mode            when a change reaches disk
     * @param flushIntervalMs the flush interval of a {@link Mode#PERIODIC} store
     */
    public PartitionedFileStorage(Path directory, Path legacyFile, Class<E> entryType, int shardCount,
                                  Mode mode, long flushIntervalMs) {
        this(directory, legacyFile == null ? null : LegacyStore.jsonFile(legacyFile, entryType), entryType,
                shardCount, mode, flushIntervalMs);
    }

    /**
     * @param legacy a single-file store to migrate into the directory while it holds no data,
     *               or {@code null}
     */
    public PartitionedFileStorage(Path directory, LegacyStore<E> legacy, Class<E> entryType, int shardCount,
                                  Mode mode, long flushIntervalMs) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be at least 1, was " + shardCount);
        }
        this.directory = directory;
        this.legacy = legacy;
        this.shardCount = shardCount;
        this.mode = mode;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        JavaType entriesType = mapper.getTypeFactory().constructMapType(Map.class, String.class, entryType);
        this.entriesReader = mapper.readerFor(entriesType);
        this.entriesWriter = mapper.writerFor(entriesType);
        this.scheduler = mode == Mode.SYNC
                ? null
                : StorageFiles.scheduleEvery("partitioned-storage-flush", flushIntervalMs, this::flushQuietly);
    }

    // --- StorageBackend ---

    @Override
    public void put(String key, Map<String, E> value) {
        applyPut(key, value);
        persistChange(allShardsOf(key));
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
        applyDelete(key);
        persistChange(allShardsOf(key));
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

    /**
     * Writes every dirty shard.
     *
     * @throws UncheckedIOException when a shard could not be written; it stays dirty and is
     *                              written again by the next flush
     */
    @Override
    public void flush() {
        int failed = writeDirtyShards();
        if (failed > 0) {
            throw new UncheckedIOException(new IOException(
                    "Could not write " + failed + " shards of " + directory + "; see the errors logged above"));
        }
    }

    @Override
    public synchronized void load() {
        // The flush schedule starts with the constructor: hold it off while the store is filled.
        fileLock.writeLock().lock();
        try {
            store.clear();
            dirty.clear();
            migrateLegacyFileIfNeeded();
            if (Files.isDirectory(directory)) {
                loadDirectory();
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    @Override
    public synchronized void clear() {
        fileLock.writeLock().lock();
        try {
            store.clear();
            dirty.clear();
            if (Files.isDirectory(directory)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
                    for (Path file : files) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException e) {
                    LOG.errorv(e, "Failed to clear {0}", directory);
                }
            }
        } finally {
            fileLock.writeLock().unlock();
        }
    }

    // --- MapEntryWriter ---

    @Override
    public void putEntry(String key, String entryKey, E entryValue) {
        persistChange(List.of(applyPutEntry(key, entryKey, entryValue)));
    }

    @Override
    public void removeEntry(String key, String entryKey) {
        ShardId id = applyRemoveEntry(key, entryKey);
        if (id != null) {
            persistChange(List.of(id));
        }
    }

    // ---

    /** Stops the flush schedule and writes what is still dirty. */
    public void shutdown() {
        if (scheduler != null) {
            StorageFiles.stop(scheduler);
        }
        flush();
    }

    /**
     * Writes the changed shards of a sync store before the call returns; a periodic store leaves
     * them to the next flush.
     *
     * @throws UncheckedIOException when a shard could not be written; the change stays in memory
     *                              and dirty, so a later flush writes it
     */
    private void persistChange(List<ShardId> changed) {
        if (mode != Mode.SYNC) {
            return;
        }
        int failed = 0;
        for (ShardId id : changed) {
            if (!writeShard(id)) {
                failed++;
            }
        }
        if (failed > 0) {
            throw new UncheckedIOException(new IOException(
                    "A change to " + changed.getFirst().key() + " in " + directory
                            + " is in memory but could not be written; see the errors logged above"));
        }
    }

    // The apply methods change memory and mark what they change dirty.

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

    /** Writes every dirty shard and returns how many could not be written. */
    private int writeDirtyShards() {
        int failed = 0;
        for (ShardId id : List.copyOf(dirty)) {
            if (!writeShard(id)) {
                failed++;
            }
        }
        return failed;
    }

    void setBeforeShardWrite(Runnable hook) {
        this.beforeShardWrite = hook;
    }

    void setBeforeSupersededDelete(Runnable hook) {
        this.beforeSupersededDelete = hook;
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

    private List<ShardId> allShardsOf(String key) {
        List<ShardId> ids = new ArrayList<>(shardCount);
        for (int shard = 0; shard < shardCount; shard++) {
            ids.add(new ShardId(key, shard));
        }
        return ids;
    }

    /**
     * The flush timer's task. It must not throw: an exception escaping a scheduled task cancels
     * every later run, so one bad flush would stop the store from ever writing again. A failure
     * is logged here and its shards stay dirty, so the next run retries them; an explicit
     * {@link #flush()} (shutdown included) reports the same failure to its caller.
     */
    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            LOG.errorv(e, "Scheduled flush of {0} failed; its unwritten shards are retried on the next one",
                    directory);
        }
    }

    /**
     * Writes one shard's file, or deletes it when the shard is empty or its key is gone. The dirty
     * mark is cleared before the shard is read, so a change racing the write marks it again and
     * is written next.
     *
     * @return {@code false} when the file could not be written; the shard is then dirty again
     */
    private boolean writeShard(ShardId id) {
        // clear() holds the write side, so a write that read a shard before the clear cannot put
        // its file back after the clear removed it. Load already holds the write side, which lets
        // it take the read side here.
        fileLock.readLock().lock();
        try {
            return writeShardLocked(id);
        } finally {
            fileLock.readLock().unlock();
        }
    }

    private boolean writeShardLocked(ShardId id) {
        Object lock = fileLocks.computeIfAbsent(id, ignored -> new Object());
        synchronized (lock) {
            if (!dirty.remove(id)) {
                return true;
            }
            Path file = fileFor(id);
            try {
                Shards<E> value = store.get(id.key());
                ConcurrentHashMap<String, E> part = value == null ? null : value.part(id.shard());
                if (part == null || part.isEmpty()) {
                    Files.deleteIfExists(file);
                    return true;
                }
                beforeShardWrite.run();
                StorageFiles.writeAtomically(file, tempFile -> writeShardContent(tempFile, id.key(), part));
                return true;
            } catch (IOException e) {
                dirty.add(id);
                LOG.errorv(e, "Failed to persist shard {0} of {1} to {2}", id.shard(), id.key(), file);
                return false;
            }
        }
    }

    private void writeShardFile(Path file, String key, Map<String, E> part) throws IOException {
        StorageFiles.writeAtomically(file, tempFile -> writeShardContent(tempFile, key, part));
    }

    private void writeShardContent(Path file, String key, Map<String, E> part) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
             JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
            generator.writeStartObject();
            generator.writeStringField(KEY_FIELD, key);
            generator.writeFieldName(ENTRIES_FIELD);
            entriesWriter.writeValue(generator, part);
            generator.writeEndObject();
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
        // first, and only then drop the old ones, so a crash in between loses nothing. An old
        // file must not outlive this load: later writes change only the current files, so the
        // next load would merge it back in and revive what they deleted or overwrote.
        writeDirtyShards();
        if (!dirty.isEmpty()) {
            throw new IllegalStateException("Could not rewrite " + dirty.size() + " shards of " + directory
                    + " from superseded files; they are kept, fix the cause and restart");
        }
        beforeSupersededDelete.run();
        for (Path file : superseded) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to remove superseded file " + file
                        + "; its entries are in the current files, remove it and restart", e);
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

    private void migrateLegacyFileIfNeeded() {
        Path staging = directory.resolveSibling(directory.getFileName() + MIGRATING_SUFFIX);
        // A staging directory left over is a migration that crashed before it was moved in place.
        deleteRecursively(staging);
        if (legacy == null) {
            return;
        }
        List<Path> sources = legacy.existingFiles();
        if (sources.isEmpty()) {
            return;
        }
        if (holdsData(directory)) {
            // Moved in place by a migration that crashed before renaming its sources, or written
            // since: either way the directory is newer than the legacy files.
            LOG.warnv("Ignoring {0}: {1} already holds data", sources, directory);
            return;
        }
        Map<String, Map<String, E>> entries;
        try {
            entries = legacy.read();
        } catch (IOException e) {
            for (Path source : sources) {
                StorageQuarantine.quarantine(source, e, LOG);
            }
            return;
        }
        // Every file is written to a staging directory that is moved in place in one step, so a
        // crash or a failed write never leaves a directory holding only part of the legacy store.
        try {
            Files.createDirectories(staging);
            for (Map.Entry<String, Map<String, E>> entry : entries.entrySet()) {
                Shards<E> shards = split(entry.getValue());
                for (int shard = 0; shard < shardCount; shard++) {
                    ConcurrentHashMap<String, E> part = shards.part(shard);
                    if (!part.isEmpty()) {
                        writeShardFile(staging.resolve(fileNameFor(entry.getKey(), shard, shardCount)),
                                entry.getKey(), part);
                    }
                }
            }
            if (Files.isDirectory(directory)) {
                // Empty (holdsData said so): make way for the move.
                Files.delete(directory);
            }
            Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            deleteRecursively(staging);
            // Starting empty would let new writes fill the directory, after which the legacy
            // files are ignored for good: refuse to start instead.
            throw new UncheckedIOException("Failed to migrate " + sources + " into " + directory
                    + "; the legacy files are untouched", e);
        }
        for (Path source : sources) {
            Path migrated = source.resolveSibling(source.getFileName() + ".migrated");
            try {
                Files.move(source, migrated, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.errorv(e, "Migrated {0} into {1} but could not rename it", source, directory);
            }
        }
        LOG.infov("Migrated {0} keys from {1} into {2}; the old files now end in .migrated",
                entries.size(), sources, directory);
    }

    /** Whether {@code dir} holds anything at all. */
    private static boolean holdsData(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            return files.iterator().hasNext();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list " + dir, e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to remove " + dir, e);
        }
    }

    /**
     * A single-file store to migrate into an empty directory on load: the file a {@code
     * persistent} or {@code hybrid} store writes, or the snapshot and log of a {@code wal} one.
     */
    public interface LegacyStore<E> {

        /** The files the store occupies that exist now; empty when there is nothing to migrate. */
        List<Path> existingFiles();

        /** Every key and its map, read without changing what the files hold. */
        Map<String, Map<String, E>> read() throws IOException;

        /** The plain JSON file of a {@code persistent} or {@code hybrid} store. */
        static <E> LegacyStore<E> jsonFile(Path file, Class<E> entryType) {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            JavaType type = mapper.getTypeFactory().constructMapType(Map.class,
                    mapper.getTypeFactory().constructType(String.class),
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, entryType));
            return new LegacyStore<>() {
                @Override
                public List<Path> existingFiles() {
                    return Files.exists(file) ? List.of(file) : List.of();
                }

                @Override
                public Map<String, Map<String, E>> read() throws IOException {
                    return mapper.readValue(file.toFile(), type);
                }
            };
        }
    }
}
