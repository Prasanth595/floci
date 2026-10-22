package io.github.hectorvent.floci.core.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** {@link PartitionedFileStorage} in {@link PartitionedFileStorage.Mode#JOURNAL} mode. */
class PartitionedFileStorageJournalTest {

    private static final FileTime LONG_AGO = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

    @TempDir
    Path tempDir;

    private final List<PartitionedFileStorage<String>> opened = new ArrayList<>();

    @AfterEach
    void tearDown() {
        opened.forEach(PartitionedFileStorage::shutdown);
    }

    /** Opens the store the way a restart after a crash would: whatever an earlier instance left. */
    private PartitionedFileStorage<String> open(int shards) {
        PartitionedFileStorage<String> storage = new PartitionedFileStorage<>(tempDir.resolve("items"),
                tempDir.resolve("items.json"), String.class, shards, PartitionedFileStorage.Mode.JOURNAL,
                Long.MAX_VALUE);
        storage.load();
        opened.add(storage);
        return storage;
    }

    private Path journal() {
        return tempDir.resolve("items").resolve(PartitionedFileStorage.JOURNAL_FILE);
    }

    private long shardFileCount() throws Exception {
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json")).count();
        }
    }

    @Test
    void aWriteCostsOneRecordWhateverTheSizeOfItsKey() throws Exception {
        PartitionedFileStorage<String> storage = open(4);
        for (int i = 0; i < 100; i++) {
            storage.putEntry("big", "entry-" + i, "value");
        }
        long before = Files.size(journal());
        storage.putEntry("big", "entry-x", "value");
        long costAtOneHundred = Files.size(journal()) - before;

        for (int i = 100; i < 10_000; i++) {
            storage.putEntry("big", "entry-" + i, "value");
        }
        before = Files.size(journal());
        storage.putEntry("big", "entry-y", "value");
        long costAtTenThousand = Files.size(journal()) - before;

        assertTrue(costAtOneHundred > 0);
        assertEquals(costAtOneHundred, costAtTenThousand);
        assertEquals(0, shardFileCount(), "shard files are only written by compaction");
    }

    @Test
    void aRestartWithoutCompactionReplaysEveryChange() {
        PartitionedFileStorage<String> crashed = open(4);
        crashed.put("table", Map.of("a", "1", "b", "2", "c", "3"));
        crashed.putEntry("table", "d", "4");
        crashed.removeEntry("table", "a");
        crashed.putEntry("gone", "x", "1");
        crashed.delete("gone");

        PartitionedFileStorage<String> restarted = open(4);

        assertEquals(Map.of("b", "2", "c", "3", "d", "4"), restarted.get("table").orElseThrow());
        assertTrue(restarted.get("gone").isEmpty());
    }

    @Test
    void compactionRewritesOnlyChangedShardsAndEmptiesTheJournal() throws Exception {
        PartitionedFileStorage<String> storage = open(8);
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < 200; i++) {
            entries.put("entry-" + i, "value-" + i);
        }
        storage.put("big", entries);
        storage.flush();
        assertEquals(8, shardFileCount());
        assertEquals(0, Files.size(journal()));
        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            for (Path file : files.toList()) {
                Files.setLastModifiedTime(file, LONG_AGO);
            }
        }
        Path touched = storage.fileFor("big", "entry-7");

        storage.putEntry("big", "entry-7", "changed");
        storage.flush();

        try (Stream<Path> files = Files.list(tempDir.resolve("items"))) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                assertEquals(file.equals(touched), !LONG_AGO.equals(Files.getLastModifiedTime(file)),
                        file.getFileName().toString());
            }
        }
        assertEquals(0, Files.size(journal()));
        entries.put("entry-7", "changed");
        assertEquals(entries, open(8).get("big").orElseThrow());
    }

    @Test
    void anIdleCompactionWritesNothing() throws Exception {
        PartitionedFileStorage<String> storage = open(1);
        storage.putEntry("table", "a", "1");
        storage.flush();
        Path file = storage.fileFor("table");
        Files.setLastModifiedTime(file, LONG_AGO);

        storage.flush();

        assertEquals(LONG_AGO, Files.getLastModifiedTime(file));
    }

    @Test
    void aTornTailIsCutAndLaterRecordsSurvive() throws Exception {
        PartitionedFileStorage<String> crashed = open(1);
        crashed.putEntry("table", "a", "1");
        crashed.putEntry("table", "b", "2");
        Files.write(journal(), new byte[]{0x01, 0x00, 0x00, 0x7f}, StandardOpenOption.APPEND);

        PartitionedFileStorage<String> restarted = open(1);
        assertEquals(Map.of("a", "1", "b", "2"), restarted.get("table").orElseThrow());
        restarted.putEntry("table", "c", "3");

        assertEquals(Map.of("a", "1", "b", "2", "c", "3"), open(1).get("table").orElseThrow());
    }

    @Test
    void replayOverShardFilesAppliesOnlyTheNewerChanges() {
        PartitionedFileStorage<String> storage = open(2);
        storage.put("table", Map.of("a", "1", "b", "2"));
        storage.flush();
        storage.putEntry("table", "a", "changed");
        storage.removeEntry("table", "b");

        assertEquals(Map.of("a", "changed"), open(2).get("table").orElseThrow());
    }

    @Test
    void clearRemovesTheJournalAndEveryFile() throws Exception {
        PartitionedFileStorage<String> storage = open(2);
        storage.put("table", Map.of("a", "1"));
        storage.flush();
        storage.putEntry("table", "b", "2");

        storage.clear();

        assertEquals(0, shardFileCount());
        assertTrue(open(2).keys().isEmpty());
    }

    @Test
    void shutdownCompacts() throws Exception {
        PartitionedFileStorage<String> storage = open(1);
        storage.putEntry("table", "a", "1");

        storage.shutdown();
        opened.remove(storage);

        assertEquals(0, Files.size(journal()));
        assertEquals(1, shardFileCount());
    }
}
