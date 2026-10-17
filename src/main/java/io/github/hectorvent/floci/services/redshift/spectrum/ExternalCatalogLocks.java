package io.github.hectorvent.floci.services.redshift.spectrum;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Coordinates Spectrum catalog mutations with readers, including aliases of the same Glue database. */
final class ExternalCatalogLocks {
    private static final Map<CatalogKey, ReentrantReadWriteLock> LOCKS = new ConcurrentHashMap<>();

    private ExternalCatalogLocks() {
    }

    static Guard read(ExternalSchemaBinding binding, boolean transactionOpen) {
        return acquire(binding, false, transactionOpen);
    }

    static Guard write(ExternalSchemaBinding binding) {
        return acquire(binding, true, false);
    }

    private static Guard acquire(ExternalSchemaBinding binding, boolean write, boolean transactionOpen) {
        CatalogKey key = new CatalogKey(binding.accountId(), binding.glueDatabase().toLowerCase(Locale.ROOT));
        ReentrantReadWriteLock catalog = LOCKS.computeIfAbsent(key, ignored -> new ReentrantReadWriteLock(true));
        Lock lock = write ? catalog.writeLock() : catalog.readLock();
        try {
            if (transactionOpen) {
                if (!lock.tryLock(30, TimeUnit.SECONDS)) {
                    throw new SpectrumReadException("58030", "Timed out waiting for external catalog access");
                }
            } else {
                lock.lockInterruptibly();
            }
            return new Guard(lock);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpectrumReadException("58030", "Interrupted while waiting for external catalog access", exception);
        }
    }

    private record CatalogKey(String accountId, String databaseName) { }

    record Guard(Lock lock) implements AutoCloseable {
        @Override
        public void close() {
            lock.unlock();
        }
    }
}
