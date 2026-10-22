package io.github.hectorvent.floci.core.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
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

class PartitionedFileStorageTest {

    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

    @TempDir
    Path tempDir;

    private final List<PartitionedFileStorage<String>> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(PartitionedFileStorage::shutdown);
    }

    private PartitionedFileStorage<String> open(PartitionedFileStorage.Mode mode) {
        PartitionedFileStorage<String> storage = new PartitionedFileStorage<>(tempDir.resolve("items"),
                tempDir.resolve("items.json"), String.class, mode, 60_000);
        storage.load();
        opened.add(storage);
        return storage;
    }

    @Test
    void syncEntryWriteRewritesOnlyItsOwnKeysFile() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("big", Map.of("a", "1", "b", "2"));
        storage.putEntry("busy", "node-1", "beat-1");
        Path bigFile = storage.fileFor("big");
        Files.setLastModifiedTime(bigFile, LONG_AGO);

        storage.putEntry("busy", "node-1", "beat-2");
        storage.removeEntry("busy", "node-1");
        storage.putEntry("busy", "node-2", "beat-1");

        assertEquals(LONG_AGO, Files.getLastModifiedTime(bigFile));
        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);
        assertEquals(Map.of("node-2", "beat-1"), reloaded.get("busy").orElseThrow());
        assertEquals(Map.of("a", "1", "b", "2"), reloaded.get("big").orElseThrow());
    }

    @Test
    void periodicStoreWritesOnlyDirtyKeysOnFlush() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.PERIODIC);
        storage.put("big", Map.of("a", "1"));
        storage.putEntry("busy", "node-1", "beat-1");
        assertFalse(Files.exists(storage.fileFor("big")), "a periodic store writes nothing before a flush");

        storage.flush();
        Path bigFile = storage.fileFor("big");
        Files.setLastModifiedTime(bigFile, LONG_AGO);
        storage.putEntry("busy", "node-1", "beat-2");
        storage.flush();

        assertEquals(LONG_AGO, Files.getLastModifiedTime(bigFile));
        assertEquals(Map.of("node-1", "beat-2"), open(PartitionedFileStorage.Mode.PERIODIC).get("busy").orElseThrow());
    }

    @Test
    void deletingAKeyRemovesItsFile() {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("gone", Map.of("a", "1"));
        Path file = storage.fileFor("gone");
        assertTrue(Files.exists(file));

        storage.delete("gone");

        assertFalse(Files.exists(file));
        assertTrue(open(PartitionedFileStorage.Mode.SYNC).get("gone").isEmpty());
    }

    @Test
    void removingAnEntryOfAMissingKeyDoesNotCreateIt() {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.removeEntry("missing", "a");
        assertTrue(storage.get("missing").isEmpty());
        assertFalse(Files.exists(storage.fileFor("missing")));
    }

    @Test
    void keysOfAnyLengthAndCharacterMapToSafeDistinctFiles() {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        String longKey = "000000000000/us-east-1::" + "t".repeat(255);
        String otherLongKey = "000000000000/us-east-1::" + "t".repeat(254) + "u";
        storage.put(longKey, Map.of("a", "1"));
        storage.put(otherLongKey, Map.of("a", "2"));
        storage.put("../escape", Map.of("a", "3"));

        assertNotEquals(storage.fileFor(longKey), storage.fileFor(otherLongKey));
        assertTrue(storage.fileFor(longKey).getFileName().toString().length() < 120);
        assertEquals(tempDir.resolve("items"), storage.fileFor("../escape").getParent());
        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);
        assertEquals("1", reloaded.get(longKey).orElseThrow().get("a"));
        assertEquals("2", reloaded.get(otherLongKey).orElseThrow().get("a"));
        assertEquals("3", reloaded.get("../escape").orElseThrow().get("a"));
    }

    @Test
    void firstLoadMigratesTheLegacySingleFile() throws Exception {
        Path legacy = tempDir.resolve("items.json");
        new ObjectMapper().writeValue(legacy.toFile(), Map.of(
                "000000000000/us-east-1::a", Map.of("k1", "v1"),
                "000000000000/us-east-1::b", Map.of("k2", "v2")));

        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.PERIODIC);

        assertEquals(Map.of("k1", "v1"), storage.get("000000000000/us-east-1::a").orElseThrow());
        assertFalse(Files.exists(legacy));
        assertTrue(Files.exists(tempDir.resolve("items.json.migrated")));
        assertTrue(Files.exists(storage.fileFor("000000000000/us-east-1::b")),
                "migration writes the files at once, not on the next flush");
        assertEquals(Map.of("k2", "v2"),
                open(PartitionedFileStorage.Mode.SYNC).get("000000000000/us-east-1::b").orElseThrow());
    }

    @Test
    void anUnreadableFileIsQuarantinedAndTheRestStillLoad() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("good", Map.of("a", "1"));
        Files.writeString(tempDir.resolve("items").resolve("broken-0000000000000000.json"), "{not json");

        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);

        assertEquals(Map.of("a", "1"), reloaded.get("good").orElseThrow());
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            assertTrue(files.noneMatch(f -> f.getFileName().toString().equals("broken-0000000000000000.json")));
        }
    }

    @Test
    void aFileUnderAnOldNameIsRewrittenUnderItsCurrentName() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("key", Map.of("a", "1"));
        Path current = storage.fileFor("key");
        Path old = current.resolveSibling("old-name.json");
        Files.move(current, old);

        PartitionedFileStorage<String> reloaded = open(PartitionedFileStorage.Mode.SYNC);

        assertEquals(Map.of("a", "1"), reloaded.get("key").orElseThrow());
        assertTrue(Files.exists(current));
        assertFalse(Files.exists(old));
    }

    @Test
    void clearRemovesEveryFile() throws Exception {
        PartitionedFileStorage<String> storage = open(PartitionedFileStorage.Mode.SYNC);
        storage.put("a", Map.of("x", "1"));
        storage.put("b", Map.of("x", "2"));

        storage.clear();

        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            assertEquals(0, files.count());
        }
        assertTrue(open(PartitionedFileStorage.Mode.SYNC).keys().isEmpty());
    }
}
