package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.PointInTimeChange;
import io.github.hectorvent.floci.services.dynamodb.model.PointInTimeHistory;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The change history behind point-in-time recovery. A change keeps what it replaced, so a table at
 * a time is the live table with every later change undone.
 */
final class DynamoDbPointInTimeRecovery {

    record RestoreWindow(Instant earliest, Instant latest) {}

    private final StorageBackend<String, PointInTimeHistory> store;
    private final Duration latestLag;
    // Keyed like DynamoDbService.itemsByTable: "<account>/<region>::<table>".
    private final ConcurrentHashMap<String, PointInTimeHistory> histories = new ConcurrentHashMap<>();

    DynamoDbPointInTimeRecovery(StorageBackend<String, PointInTimeHistory> store, String defaultAccountId,
                                Duration latestLag) {
        this.store = store;
        this.latestLag = latestLag;
        if (store instanceof AccountAwareStorageBackend<PointInTimeHistory> aware) {
            aware.scanAllAccountsRaw().forEach((key, history) -> histories.put(key, history.copy()));
        } else {
            for (String storageKey : store.keys()) {
                store.get(storageKey).ifPresent(history ->
                        histories.put(key(defaultAccountId, storageKey), history.copy()));
            }
        }
    }

    void clear() {
        histories.clear();
    }

    void start(String accountId, String storageKey) {
        PointInTimeHistory history = new PointInTimeHistory(now().truncatedTo(ChronoUnit.SECONDS), new ArrayList<>());
        histories.put(key(accountId, storageKey), history);
        synchronized (history) {
            persist(accountId, storageKey, history);
        }
    }

    void drop(String accountId, String storageKey) {
        histories.remove(key(accountId, storageKey));
        if (store instanceof AccountAwareStorageBackend<PointInTimeHistory> aware) {
            aware.deleteForAccount(accountId, storageKey);
        } else {
            store.delete(storageKey);
        }
    }

    void recordItem(String accountId, String storageKey, TableDefinition table, String itemKey, JsonNode oldItem) {
        if (table.isPointInTimeRecoveryEnabled()) {
            record(accountId, storageKey, table,
                    new PointInTimeChange(now(), itemKey, oldItem != null ? oldItem.deepCopy() : null));
        }
    }

    /** AWS words an InvalidRestoreTimeException with the latest time to the nanosecond. */
    RestoreWindow window(String accountId, String storageKey, TableDefinition table) {
        Instant now = Instant.now();
        PointInTimeHistory history = history(accountId, storageKey);
        Instant start;
        synchronized (history) {
            start = history.getStart();
        }
        Instant earliest = later(start, now.minus(recoveryPeriod(table)));
        return new RestoreWindow(earliest, later(earliest, now.minus(latestLag)));
    }

    /** An item changed after {@code time} takes the value that its first change after it replaced. */
    List<JsonNode> itemsAt(String accountId, String storageKey, Map<String, JsonNode> liveItems, Instant time) {
        Map<String, JsonNode> items = new HashMap<>(liveItems);
        Set<String> undone = new HashSet<>();
        PointInTimeHistory history = history(accountId, storageKey);
        synchronized (history) {
            for (PointInTimeChange change : history.getChanges()) {
                String itemKey = change.getItemKey();
                if (!change.getTime().isAfter(time) || !undone.add(itemKey)) {
                    continue;
                }
                if (change.getOldItem() == null) {
                    items.remove(itemKey);
                } else {
                    items.put(itemKey, change.getOldItem());
                }
            }
        }
        return new ArrayList<>(items.values());
    }

    private void record(String accountId, String storageKey, TableDefinition table, PointInTimeChange change) {
        PointInTimeHistory history = history(accountId, storageKey);
        synchronized (history) {
            history.getChanges().add(change);
            Instant cutoff = change.getTime().minus(recoveryPeriod(table));
            List<PointInTimeChange> changes = history.getChanges();
            int expired = 0;
            while (expired < changes.size() && changes.get(expired).getTime().isBefore(cutoff)) {
                expired++;
            }
            changes.subList(0, expired).clear();
            history.setStart(later(history.getStart(), cutoff));
            persist(accountId, storageKey, history);
        }
    }

    /** A table that had point-in-time recovery enabled before Floci kept a history starts one now. */
    private PointInTimeHistory history(String accountId, String storageKey) {
        return histories.computeIfAbsent(key(accountId, storageKey), ignored ->
                new PointInTimeHistory(now().truncatedTo(ChronoUnit.SECONDS), new ArrayList<>()));
    }

    // The store gets a copy, since this history keeps changing after the call.
    private void persist(String accountId, String storageKey, PointInTimeHistory history) {
        if (store instanceof AccountAwareStorageBackend<PointInTimeHistory> aware) {
            aware.putForAccount(accountId, storageKey, history.copy());
        } else {
            store.put(storageKey, history.copy());
        }
    }

    private static Duration recoveryPeriod(TableDefinition table) {
        return Duration.ofDays(table.getPointInTimeRecoveryRecoveryPeriodInDays());
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String key(String accountId, String storageKey) {
        return accountId + "/" + storageKey;
    }
}
