package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RequestContext;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Factory that creates {@link AccountAwareStorageBackend} instances based on configuration.
 * Every backend is wrapped in an account-aware decorator so resources are automatically
 * namespaced by the account ID of the calling credential.
 * Tracks all created backends for lifecycle management.
 */
@ApplicationScoped
public class StorageFactory {

    private static final Logger LOG = Logger.getLogger(StorageFactory.class);

    private final EmulatorConfig config;
    private final ServiceConfigAccess serviceConfigAccess;
    private final List<StorageBackend<?, ?>> allBackends = new ArrayList<>();
    // A file path identifies one logical store: callers sharing a path are expected to agree on
    // its value type and storage mode. The first create() wins; repeat calls reuse that backend.
    private final Map<Path, StorageBackend<?, ?>> backendsByPath = new HashMap<>();
    private final List<HybridStorage<?, ?>> hybridBackends = new ArrayList<>();
    private final List<WalStorage<?, ?>> walBackends = new ArrayList<>();
    private final List<PartitionedFileStorage<?>> partitionedBackends = new ArrayList<>();
    // Backends whose initial load has completed. create() loads each backend exactly once and
    // loadAll() only picks up backends that were never loaded, so neither path replays a store
    // that is already live or opens a second WAL writer on top of the first.
    private final Set<StorageBackend<?, ?>> loadedBackends = Collections.newSetFromMap(new IdentityHashMap<>());

    @Inject
    Instance<RequestContext> requestContextInstance;

    @Inject
    public StorageFactory(EmulatorConfig config, ServiceConfigAccess serviceConfigAccess) {
        this.config = config;
        this.serviceConfigAccess = serviceConfigAccess;
    }

    /**
     * Create an account-aware storage backend for the given service.
     * All keys are automatically prefixed with the current account ID derived from
     * the request credential. Async workers should use the {@code *ForAccount} overloads
     * on {@link AccountAwareStorageBackend} with the account ID stored on the resource model.
     *
     * @param serviceName   the service name (ssm, sqs, s3, …)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     */
    public synchronized <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                 TypeReference<Map<String, V>> typeReference) {
        return create(serviceName, fileName, typeReference, WriteProfile.DEFAULT);
    }

    /**
     * Create an account-aware storage backend for the given service with a write profile.
     * The profile only matters under {@code persistent} mode: an {@link WriteProfile#APPEND_HEAVY}
     * store is journaled to {@code <file>.wal} and its JSON file becomes the snapshot that
     * compaction rewrites, instead of the file being rewritten on every mutation. The first
     * {@code create()} for a path decides the profile; repeat calls reuse that backend.
     *
     * @param serviceName   the service name (ssm, sqs, s3, …)
     * @param fileName      the JSON file name for persistent storage
     * @param typeReference Jackson type reference for deserialization
     * @param profile       how the store is written
     */
    public synchronized <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                 TypeReference<Map<String, V>> typeReference,
                                                 WriteProfile profile) {
        String mode = resolveMode(serviceName);
        long flushInterval = resolveFlushInterval(serviceName);
        Path basePath = Path.of(config.storage().persistentPath());
        Path filePath = basePath.resolve(fileName);

        // Reuse an existing backend for the same file. Handing out a second backend bound to the
        // same path creates a duplicate in-memory store; on shutdown the stale duplicate flushes
        // after the active instance and clobbers persisted state (issue #1921).
        StorageBackend<?, ?> existing = backendsByPath.get(filePath);
        if (existing != null) {
            LOG.debugv("Reusing existing {0} storage for service {1} (file: {2})", mode, serviceName, filePath);
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<V> typed = (AccountAwareStorageBackend<V>) existing;
            return typed;
        }

        LOG.debugv("Creating {0} storage for service {1} (file: {2})", mode, serviceName, filePath);

        StorageBackend<String, V> inner = switch (mode) {
            case "memory" -> new InMemoryStorage<>();
            case "persistent" -> {
                if (profile != WriteProfile.APPEND_HEAVY) {
                    yield new PersistentStorage<>(filePath, typeReference);
                }
                // The store file doubles as the WAL snapshot: a file written by the plain
                // persistent backend is the first snapshot, and after a clean shutdown the file
                // is current again. Only the .wal file next to it is new.
                Path walFilePath = basePath.resolve(fileName.replace(".json", ".wal"));
                WalStorage<String, V> wal = new WalStorage<>(filePath, walFilePath, typeReference,
                        config.storage().wal().compactionIntervalMs());
                walBackends.add(wal);
                LOG.infov("Journaling {0} for service {1} to {2}; the store file is rewritten on the WAL compaction interval",
                        filePath, serviceName, walFilePath);
                yield wal;
            }
            case "hybrid" -> {
                HybridStorage<String, V> hybrid = new HybridStorage<>(filePath, typeReference, flushInterval);
                hybridBackends.add(hybrid);
                yield hybrid;
            }
            case "wal" -> {
                Path snapshotPath = basePath.resolve(fileName.replace(".json", "-snapshot.json"));
                Path walFilePath = basePath.resolve(fileName.replace(".json", ".wal"));
                long compactionInterval = config.storage().wal().compactionIntervalMs();
                WalStorage<String, V> wal = new WalStorage<>(snapshotPath, walFilePath, typeReference, compactionInterval);
                walBackends.add(wal);
                yield wal;
            }
            default -> throw new IllegalArgumentException("Unknown storage mode: " + mode);
        };

        AccountAwareStorageBackend<V> backend = new AccountAwareStorageBackend<>(
                inner, requestContextInstance, config.defaultAccountId());
        // create() owns the initial load. Most services are lazily instantiated and only reach
        // this point after the lifecycle's loadAll() has already run, so a backend that is not
        // loaded here would serve an empty store and (for WAL) never open its writer (#71).
        loadOnce(backend);
        allBackends.add(backend);
        backendsByPath.put(filePath, backend);
        return backend;
    }

    /**
     * Create an account-aware store whose values are maps, persisted under a directory named
     * after {@code fileName} (without {@code .json}) as {@code shardCount} files per key, so a
     * change to one entry rewrites only the file of its shard. Writers that change one map entry at a time use
     * {@link AccountAwareStorageBackend#putEntry} and {@link AccountAwareStorageBackend#removeEntry}
     * when {@link AccountAwareStorageBackend#writesEntries()} is true.
     *
     * <p>{@code persistent} writes a shard's file on every change and {@code hybrid} writes dirty
     * shards on the flush interval. {@code wal} journals one record per changed entry and writes
     * dirty shards on the WAL compaction interval. {@code memory} keeps the store in memory. An
     * existing single-file store (a {@code wal}-mode snapshot and log included) is migrated into
     * the directory on first load.
     */
    public synchronized <E> AccountAwareStorageBackend<Map<String, E>> createPartitioned(
            String serviceName, String fileName, TypeReference<Map<String, Map<String, E>>> typeReference,
            Class<E> entryType, int shardCount) {
        String mode = resolveMode(serviceName);
        if ("memory".equals(mode)) {
            return create(serviceName, fileName, typeReference);
        }
        Path basePath = Path.of(config.storage().persistentPath());
        Path directory = basePath.resolve(fileName.replace(".json", ""));
        StorageBackend<?, ?> existing = backendsByPath.get(directory);
        if (existing != null) {
            @SuppressWarnings("unchecked")
            AccountAwareStorageBackend<Map<String, E>> typed = (AccountAwareStorageBackend<Map<String, E>>) existing;
            return typed;
        }
        Path legacyFile = basePath.resolve(fileName);
        PartitionedFileStorage.Mode writeMode;
        long interval;
        switch (mode) {
            case "persistent" -> {
                writeMode = PartitionedFileStorage.Mode.SYNC;
                interval = 0;
            }
            case "hybrid" -> {
                writeMode = PartitionedFileStorage.Mode.PERIODIC;
                interval = resolveFlushInterval(serviceName);
            }
            case "wal" -> {
                writeMode = PartitionedFileStorage.Mode.JOURNAL;
                interval = config.storage().wal().compactionIntervalMs();
                if (!Files.isDirectory(directory)) {
                    legacyFile = foldLegacyWal(basePath, fileName, typeReference, legacyFile);
                }
            }
            default -> throw new IllegalArgumentException("Unknown storage mode: " + mode);
        }
        PartitionedFileStorage<E> inner = new PartitionedFileStorage<>(directory, legacyFile, entryType,
                shardCount, writeMode, interval);
        partitionedBackends.add(inner);
        LOG.infov("Storing {0} for service {1} in {2}, {3} file(s) per key", fileName, serviceName, directory,
                shardCount);
        AccountAwareStorageBackend<Map<String, E>> backend = new AccountAwareStorageBackend<>(
                inner, requestContextInstance, config.defaultAccountId());
        loadOnce(backend);
        allBackends.add(backend);
        backendsByPath.put(directory, backend);
        return backend;
    }

    /**
     * The single file to migrate into a {@code wal}-mode partitioned store. A store last run as a
     * {@link WalStorage} keeps its data in a snapshot plus a log; the log is folded into the
     * snapshot first so no logged change is lost, and the snapshot is what gets migrated.
     * Otherwise the plain store file, written by {@code persistent} or {@code hybrid}, is used.
     */
    private static <V> Path foldLegacyWal(Path basePath, String fileName, TypeReference<Map<String, V>> typeReference,
                                          Path plainFile) {
        Path snapshot = basePath.resolve(fileName.replace(".json", "-snapshot.json"));
        Path log = basePath.resolve(fileName.replace(".json", ".wal"));
        if (!Files.exists(snapshot) && !Files.exists(log)) {
            return plainFile;
        }
        WalStorage<String, V> legacy = new WalStorage<>(snapshot, log, typeReference, Long.MAX_VALUE);
        legacy.load();
        legacy.shutdown();
        try {
            Files.deleteIfExists(log);
        } catch (IOException e) {
            LOG.warnv(e, "Folded {0} into {1} but could not remove it", log, snapshot);
        }
        return snapshot;
    }

    /** Load every managed backend that has not been loaded yet. Safe to call repeatedly. */
    public synchronized void loadAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            loadOnce(backend);
        }
    }

    private void loadOnce(StorageBackend<?, ?> backend) {
        if (loadedBackends.contains(backend)) {
            return;
        }
        backend.load();
        loadedBackends.add(backend);
    }

    /** Flush all storage backends to disk. */
    public synchronized void flushAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.flush();
        }
    }

    /** Clear all storage backends. */
    public synchronized void clearAll() {
        for (StorageBackend<?, ?> backend : allBackends) {
            backend.clear();
        }
        flushAll();
    }

    /**
     * Shutdown all managed backends (stop schedulers, close connections). The final flush runs
     * first: a WAL flush compacts and reopens the writer, so flushing after shutdown would leave
     * every WAL backend with an open writer that nothing closes.
     */
    public synchronized void shutdownAll() {
        flushAll();
        for (HybridStorage<?, ?> hybrid : hybridBackends) {
            hybrid.shutdown();
        }
        for (WalStorage<?, ?> wal : walBackends) {
            wal.shutdown();
        }
        for (PartitionedFileStorage<?> partitioned : partitionedBackends) {
            partitioned.shutdown();
        }
    }

    private String resolveMode(String serviceName) {
        return serviceConfigAccess.storageMode(serviceName);
    }

    private long resolveFlushInterval(String serviceName) {
        return serviceConfigAccess.storageFlushInterval(serviceName);
    }
}
