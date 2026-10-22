package io.github.hectorvent.floci.services.dynamodb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PartitionedFileStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.dynamodb.model.AttributeDefinition;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** DynamoDB items on a store that persists one table file at a time and one item at a time. */
class DynamoDbPartitionedItemStoreTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "111111111111";
    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<PartitionedFileStorage<JsonNode>> opened = new ArrayList<>();
    private final RegionResolver resolver = new RegionResolver(REGION, ACCOUNT);

    @TempDir
    Path tempDir;

    private StorageBackend<String, TableDefinition> tableStore;
    private DynamoDbService service;

    @BeforeEach
    void setUp() {
        tableStore = AccountAwareStorageBackend.inMemory(ACCOUNT);
        service = new DynamoDbService(tableStore, openItemStore(), resolver);
        createTable(service, "Logs");
        createTable(service, "Nodes");
    }

    @AfterEach
    void tearDown() {
        opened.forEach(PartitionedFileStorage::shutdown);
    }

    @Test
    void writesToOneTableLeaveAnotherTablesFileUntouched() throws Exception {
        for (int i = 0; i < 50; i++) {
            service.putItem("Logs", item("entry-" + i, "payload"), REGION);
        }
        Path logsFile = tableFile("Logs");
        Files.setLastModifiedTime(logsFile, LONG_AGO);

        service.putItem("Nodes", item("node-1", "beat-1"), REGION);
        service.updateItem("Nodes", key("node-1"), null, "SET v = :v", null,
                mapper.createObjectNode().set(":v", attribute("beat-2")), "NONE", REGION);
        service.putItem("Nodes", item("node-2", "beat-1"), REGION);
        service.deleteItem("Nodes", key("node-2"), REGION);

        assertEquals(LONG_AGO, Files.getLastModifiedTime(logsFile));
        DynamoDbService restarted = restart();
        assertEquals("beat-2", value(restarted.getItem("Nodes", key("node-1"), REGION)));
        assertNull(restarted.getItem("Nodes", key("node-2"), REGION));
        assertEquals("payload", value(restarted.getItem("Logs", key("entry-49"), REGION)));
    }

    @Test
    void batchAndTransactWritesSurviveARestart() {
        service.putItem("Nodes", item("gone", "x"), REGION);
        service.batchWriteItem(Map.of("Nodes", List.of(
                putRequest(item("batch-1", "b1")),
                deleteRequest(key("gone")))), REGION);
        service.transactWriteItems(List.of(
                transactPut("Logs", item("tx-1", "t1")),
                transactPut("Nodes", item("tx-2", "t2"))), REGION);

        DynamoDbService restarted = restart();

        assertEquals("b1", value(restarted.getItem("Nodes", key("batch-1"), REGION)));
        assertNull(restarted.getItem("Nodes", key("gone"), REGION));
        assertEquals("t1", value(restarted.getItem("Logs", key("tx-1"), REGION)));
        assertEquals("t2", value(restarted.getItem("Nodes", key("tx-2"), REGION)));
    }

    @Test
    void anItemStoredUnderAnOlderKeyStaysDeletedAfterARestart() {
        AccountAwareStorageBackend<Map<String, JsonNode>> itemStore = openItemStore();
        itemStore.put(REGION + "::Nodes", Map.of("legacy-address-of-node-9", item("node-9", "old")));
        DynamoDbService rekeyed = new DynamoDbService(tableStore, itemStore, resolver);

        rekeyed.deleteItem("Nodes", key("node-9"), REGION);

        assertNull(restart().getItem("Nodes", key("node-9"), REGION));
    }

    @Test
    void deletingATableRemovesItsFile() throws Exception {
        service.putItem("Nodes", item("node-1", "beat-1"), REGION);
        assertTrue(Files.exists(tableFile("Nodes")));

        service.deleteTable("Nodes", REGION);

        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().contains("Nodes")));
        }
    }

    @Test
    void expiredItemsTheSweeperRemovesStayRemovedAfterARestart() {
        service.updateTimeToLive("Nodes", "expires", true, REGION);
        ObjectNode expired = item("node-1", "beat-1");
        expired.set("expires", mapper.createObjectNode().put("N", "1"));
        service.putItem("Nodes", expired, REGION);
        service.putItem("Nodes", item("node-2", "beat-1"), REGION);

        service.deleteScannedItems(service.scanExpiredItems());

        DynamoDbService restarted = restart();
        assertNull(restarted.getItem("Nodes", key("node-1"), REGION));
        assertEquals("beat-1", value(restarted.getItem("Nodes", key("node-2"), REGION)));
    }

    private AccountAwareStorageBackend<Map<String, JsonNode>> openItemStore() {
        PartitionedFileStorage<JsonNode> inner = new PartitionedFileStorage<>(tempDir.resolve("items"), null,
                JsonNode.class, PartitionedFileStorage.Mode.SYNC, 60_000);
        inner.load();
        opened.add(inner);
        return new AccountAwareStorageBackend<>(inner, null, ACCOUNT);
    }

    private DynamoDbService restart() {
        return new DynamoDbService(tableStore, openItemStore(), resolver);
    }

    private Path tableFile(String table) throws Exception {
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            return files.filter(f -> f.getFileName().toString().contains(table)).findFirst().orElseThrow();
        }
    }

    private void createTable(DynamoDbService target, String name) {
        target.createTable(name, List.of(new KeySchemaElement("id", "HASH")),
                List.of(new AttributeDefinition("id", "S")), 5L, 5L, REGION);
    }

    private ObjectNode key(String id) {
        ObjectNode key = mapper.createObjectNode();
        key.set("id", attribute(id));
        return key;
    }

    private ObjectNode item(String id, String value) {
        ObjectNode item = key(id);
        item.set("v", attribute(value));
        return item;
    }

    private ObjectNode attribute(String value) {
        return mapper.createObjectNode().put("S", value);
    }

    private ObjectNode putRequest(ObjectNode item) {
        ObjectNode request = mapper.createObjectNode();
        request.putObject("PutRequest").set("Item", item);
        return request;
    }

    private ObjectNode deleteRequest(ObjectNode key) {
        ObjectNode request = mapper.createObjectNode();
        request.putObject("DeleteRequest").set("Key", key);
        return request;
    }

    private ObjectNode transactPut(String table, ObjectNode item) {
        ObjectNode request = mapper.createObjectNode();
        ObjectNode put = request.putObject("Put");
        put.put("TableName", table);
        put.set("Item", item);
        return request;
    }

    private static String value(JsonNode item) {
        assertNotNull(item);
        return item.path("v").path("S").asText();
    }
}
