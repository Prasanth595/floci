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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * File storage for a store whose values are maps, written one file per key. A change to one key
 * rewrites that key's file only, so a store holding one large value and one small, busy value
 * (a large DynamoDB table next to a heartbeat table) no longer rewrites the large one for every
 * change to the small one, as {@link PersistentStorage} and {@link HybridStorage} do with their
 * single file.
 *
 * <p>Each file records its own key, so a file name only has to be unique and safe, not
 * reversible: it is a readable prefix of the key plus a hash of it.
 *
 * <p>A {@link Mode#SYNC} store writes a key's file before the mutating call returns, like
 * {@link PersistentStorage}. A {@link Mode#PERIODIC} store marks the key dirty and writes every
 * dirty key on the flush interval, like {@link HybridStorage}.
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
    private static final String KEY_FIELD = "key";
    private static final String ENTRIES_FIELD = "entries";
    private static final int READABLE_PREFIX_LENGTH = 80;

    /** When a change reaches disk. */
    public enum Mode {
        /** Before the mutating call returns. */
        SYNC,
        /** On the flush interval. */
        PERIODIC
    }

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, E>> store = new ConcurrentHashMap<>();
    private final Set<String> dirtyKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();
    private final Path directory;
    private final Path legacyFile;
    private final Mode mode;
    private final ObjectMapper mapper;
    private final ObjectReader entriesReader;
    private final ObjectWriter entriesWriter;
    private final JavaType legacyType;
    private final ScheduledExecutorService scheduler;

    /**
     * @param directory       where the per-key files live
     * @param legacyFile      a single-file store to migrate on first load, or {@code null}
     * @param entryType       the type of one map entry's value
     * @param mode            when a change reaches disk
     * @param flushIntervalMs the flush interval of a {@link Mode#PERIODIC} store
     */
    public PartitionedFileStorage(Path directory, Path legacyFile, Class<E> entryType, Mode mode,
                                  long flushIntervalMs) {
        this.directory = directory;
        this.legacyFile = legacyFile;
        this.mode = mode;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        JavaType entriesType = mapper.getTypeFactory().constructMapType(Map.class, String.class, entryType);
        this.entriesReader = mapper.readerFor(entriesType);
        this.entriesWriter = mapper.writerFor(entriesType);
        this.legacyType = mapper.getTypeFactory().constructMapType(Map.class,
                mapper.getTypeFactory().constructType(String.class), entriesType);
        if (mode == Mode.PERIODIC) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "partitioned-storage-flush");
                t.setDaemon(true);
                return t;
            });
            this.scheduler.scheduleAtFixedRate(this::flushQuietly, flushIntervalMs, flushIntervalMs,
                    TimeUnit.MILLISECONDS);
        } else {
            this.scheduler = null;
        }
    }

    // --- StorageBackend ---

    @Override
    public void put(String key, Map<String, E> value) {
        store.put(key, new ConcurrentHashMap<>(value));
        changed(key);
    }

    @Override
    public void putAll(Map<String, Map<String, E>> entries) {
        entries.forEach((key, value) -> store.put(key, new ConcurrentHashMap<>(value)));
        entries.keySet().forEach(this::changed);
    }

    /** A read-only view of the stored map; it follows later changes to the key. */
    @Override
    public Optional<Map<String, E>> get(String key) {
        ConcurrentHashMap<String, E> value = store.get(key);
        return value == null ? Optional.empty() : Optional.of(Collections.unmodifiableMap(value));
    }

    @Override
    public void delete(String key) {
        store.remove(key);
        changed(key);
    }

    @Override
    public List<Map<String, E>> scan(Predicate<String> keyFilter) {
        return store.entrySet().stream()
                .filter(e -> keyFilter.test(e.getKey()))
                .map(e -> Collections.<String, E>unmodifiableMap(e.getValue()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public Set<String> keys() {
        return Collections.unmodifiableSet(store.keySet());
    }

    @Override
    public void flush() {
        for (String key : List.copyOf(dirtyKeys)) {
            writeKey(key);
        }
    }

    @Override
    public synchronized void load() {
        store.clear();
        dirtyKeys.clear();
        if (Files.isDirectory(directory)) {
            loadDirectory();
        }
        if (store.isEmpty() && legacyFile != null && Files.exists(legacyFile)) {
            migrateLegacyFile();
        }
    }

    @Override
    public synchronized void clear() {
        store.clear();
        dirtyKeys.clear();
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to clear {0}", directory);
        }
    }

    // --- MapEntryWriter ---

    @Override
    public void putEntry(String key, String entryKey, E entryValue) {
        store.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).put(entryKey, entryValue);
        changed(key);
    }

    @Override
    public void removeEntry(String key, String entryKey) {
        ConcurrentHashMap<String, E> value = store.get(key);
        if (value != null && value.remove(entryKey) != null) {
            changed(key);
        }
    }

    // ---

    /** Stops the flush schedule and writes what is still dirty. */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    /** The file that holds {@code key}. */
    Path fileFor(String key) {
        return directory.resolve(fileNameFor(key));
    }

    static String fileNameFor(String key) {
        StringBuilder readable = new StringBuilder();
        for (int i = 0; i < key.length() && readable.length() < READABLE_PREFIX_LENGTH; i++) {
            char c = key.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            readable.append(safe ? c : '_');
        }
        return readable + "-" + shortHash(key) + FILE_SUFFIX;
    }

    private static String shortHash(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void changed(String key) {
        dirtyKeys.add(key);
        if (mode == Mode.SYNC) {
            writeKey(key);
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
     * Writes one key's file, or deletes it when the key is gone. The dirty mark is cleared before
     * the value is read, so a change racing the write marks the key again and is written next.
     */
    private void writeKey(String key) {
        Object lock = fileLocks.computeIfAbsent(key, ignored -> new Object());
        synchronized (lock) {
            if (!dirtyKeys.remove(key)) {
                return;
            }
            Path file = fileFor(key);
            try {
                ConcurrentHashMap<String, E> value = store.get(key);
                if (value == null) {
                    Files.deleteIfExists(file);
                    return;
                }
                Files.createDirectories(directory);
                Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
                try (OutputStream out = Files.newOutputStream(tempFile);
                     JsonGenerator generator = mapper.getFactory().createGenerator(out)) {
                    generator.writeStartObject();
                    generator.writeStringField(KEY_FIELD, key);
                    generator.writeFieldName(ENTRIES_FIELD);
                    entriesWriter.writeValue(generator, value);
                    generator.writeEndObject();
                }
                Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                dirtyKeys.add(key);
                LOG.errorv(e, "Failed to persist {0} to {1}", key, file);
            }
        }
    }

    private void loadDirectory() {
        List<Path> misnamed = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                String key = readFile(file);
                if (key != null && !file.equals(fileFor(key))) {
                    misnamed.add(file);
                }
            }
        } catch (IOException e) {
            LOG.errorv(e, "Failed to list {0}", directory);
        }
        LOG.infov("Loaded {0} keys from {1}", store.size(), directory);
        if (misnamed.isEmpty()) {
            return;
        }
        // Written under another naming scheme: write each key where it belongs first, and only
        // then drop the old copy, so a crash in between loses nothing.
        flush();
        if (!dirtyKeys.isEmpty()) {
            LOG.errorv("Could not rewrite {0} keys of {1}; keeping their old files", dirtyKeys.size(), directory);
            return;
        }
        for (Path file : misnamed) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOG.warnv(e, "Failed to remove superseded file {0}", file);
            }
        }
    }

    /** Reads one file into the store and returns its key, or {@code null} when it is unreadable. */
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
        store.put(key, new ConcurrentHashMap<>(entries));
        if (!file.equals(fileFor(key))) {
            dirtyKeys.add(key);
        }
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
            store.put(key, new ConcurrentHashMap<>(value));
            dirtyKeys.add(key);
        });
        flush();
        if (!dirtyKeys.isEmpty()) {
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
