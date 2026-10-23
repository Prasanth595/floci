package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.HybridStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.WalStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedshiftDataIdempotencyStoreTest {

    private static final Instant START = Instant.parse("2026-10-23T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void deadlineDoesNotSlideAndExpiresAtEightHours() {
        AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> backend =
                AccountAwareStorageBackend.inMemory("000000000000");
        RedshiftDataIdempotencyStore initial = store(backend, START);
        RedshiftDataIdempotencyStore.Scope scope = scope("000000000000", "us-east-1", "ExecuteStatement", "token");
        initial.put(scope, mapper.createObjectNode().put("Id", "original"));
        RedshiftDataIdempotencyStore later = store(backend, START.plus(Duration.ofHours(8)).minusNanos(1));
        assertEquals("original", later.find(scope).response.path("Id").asText());
        assertEquals(START.plus(Duration.ofHours(8)), later.find(scope).expiresAt);
        assertNull(store(backend, START.plus(Duration.ofHours(8))).find(scope));
    }

    @Test
    void scopesCannotCollideAndReceiptsAreIndependentSnapshots() {
        RedshiftDataIdempotencyStore store = store(AccountAwareStorageBackend.inMemory("000000000000"), START);
        RedshiftDataIdempotencyStore.Scope original = scope("000000000000", "us-east-1", "ExecuteStatement", "x:y/ z");
        ObjectNode response = mapper.createObjectNode().put("Id", "original");
        store.put(original, response);
        response.put("Id", "mutated");
        assertEquals("original", store.find(original).response.path("Id").asText());
        store.find(original).response.put("Id", "mutated-read");
        assertEquals("original", store.find(original).response.path("Id").asText());
        assertNull(store.find(scope("111111111111", "us-east-1", "ExecuteStatement", "x:y/ z")));
        assertNull(store.find(scope("000000000000", "eu-west-1", "ExecuteStatement", "x:y/ z")));
        assertNull(store.find(scope("000000000000", "us-east-1", "BatchExecuteStatement", "x:y/ z")));
        assertNull(store.find(scope("000000000000", "us-east-1", "ExecuteStatement", "X:y/ z")));
    }

    @Test
    void staleRemovalCannotDeleteAReplacementReceipt() {
        RedshiftDataIdempotencyStore store = store(AccountAwareStorageBackend.inMemory("000000000000"), START);
        RedshiftDataIdempotencyStore.Scope scope = scope("000000000000", "us-east-1", "ExecuteStatement", "token");
        store.put(scope, mapper.createObjectNode().put("Id", "new"));
        store.removeIfStatement(scope, "old");
        assertNotNull(store.find(scope));
        store.removeIfStatement(scope, "new");
        assertNull(store.find(scope));
    }

    @Test
    void sweepAndClearCoverEveryAccount() {
        AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> backend =
                AccountAwareStorageBackend.inMemory("000000000000");
        RedshiftDataIdempotencyStore store = store(backend, START);
        store.put(scope("000000000000", "us-east-1", "ExecuteStatement", "a"), mapper.createObjectNode().put("Id", "a"));
        store.put(scope("111111111111", "us-east-1", "ExecuteStatement", "a"), mapper.createObjectNode().put("Id", "b"));
        store(backend, START.plus(Duration.ofHours(8))).sweep();
        assertTrue(backend.scanAllAccountEntries(key -> true).isEmpty());
        store.put(scope("111111111111", "us-east-1", "ExecuteStatement", "a"), mapper.createObjectNode().put("Id", "c"));
        store.clear();
        assertTrue(backend.scanAllAccountEntries(key -> true).isEmpty());
    }

    @Test
    void receiptsSurvivePersistentBackendReopen(@TempDir Path directory) {
        Path file = directory.resolve("idempotency.json");
        RedshiftDataIdempotencyStore.Scope scope = scope("111111111111", "cn-north-1", "ExecuteStatement", "token");
        RedshiftDataIdempotencyStore original = store(persistent(file), START);
        assertNull(original.find(scope));
        original.put(scope, mapper.createObjectNode().put("Id", "persisted").put("CreatedAt", 123.25));
        RedshiftDataIdempotencyStore reopened = store(persistent(file), START.plusSeconds(60));
        assertEquals("persisted", reopened.find(scope).response.path("Id").asText());
        assertEquals(123.25, reopened.find(scope).response.path("CreatedAt").asDouble());
        assertEquals(START.plus(Duration.ofHours(8)), reopened.find(scope).expiresAt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hybrid", "wal"})
    void receiptsSurviveWriteBehindBackendRestart(String mode, @TempDir Path directory) {
        RedshiftDataIdempotencyStore.Scope scope = scope("111111111111", "us-east-1", "ExecuteStatement", "token");
        StorageBackend<String, RedshiftDataIdempotencyStore.Receipt> initial = writeBehind(mode, directory);
        initial.load();
        try {
            store(new AccountAwareStorageBackend<>(initial, null, "000000000000"), START)
                    .put(scope, mapper.createObjectNode().put("Id", "persisted"));
        } finally {
            shutdown(initial);
        }
        StorageBackend<String, RedshiftDataIdempotencyStore.Receipt> reopened = writeBehind(mode, directory);
        reopened.load();
        try {
            assertEquals("persisted", store(new AccountAwareStorageBackend<>(reopened, null, "000000000000"), START)
                    .find(scope).response.path("Id").asText());
        } finally {
            shutdown(reopened);
        }
    }

    private StorageBackend<String, RedshiftDataIdempotencyStore.Receipt> writeBehind(String mode, Path directory) {
        TypeReference<Map<String, RedshiftDataIdempotencyStore.Receipt>> type = new TypeReference<>() { };
        return switch (mode) {
            case "hybrid" -> new HybridStorage<>(directory.resolve("idempotency.json"), type, 60000);
            case "wal" -> new WalStorage<>(directory.resolve("idempotency.json"), directory.resolve("idempotency.wal"), type, 60000);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    private void shutdown(StorageBackend<String, RedshiftDataIdempotencyStore.Receipt> backend) {
        if (backend instanceof HybridStorage<String, RedshiftDataIdempotencyStore.Receipt> hybrid) {
            hybrid.shutdown();
        } else if (backend instanceof WalStorage<String, RedshiftDataIdempotencyStore.Receipt> wal) {
            wal.shutdown();
        }
    }

    private AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> persistent(Path file) {
        PersistentStorage<String, RedshiftDataIdempotencyStore.Receipt> delegate = new PersistentStorage<>(file,
                new TypeReference<Map<String, RedshiftDataIdempotencyStore.Receipt>>() { });
        delegate.load();
        return new AccountAwareStorageBackend<>(delegate, null, "000000000000");
    }

    private RedshiftDataIdempotencyStore store(AccountAwareStorageBackend<RedshiftDataIdempotencyStore.Receipt> backend,
                                              Instant now) {
        return new RedshiftDataIdempotencyStore(backend, Clock.fixed(now, ZoneOffset.UTC));
    }

    private RedshiftDataIdempotencyStore.Scope scope(String account, String region, String operation, String token) {
        return new RedshiftDataIdempotencyStore.Scope(account, region, operation, token);
    }
}
