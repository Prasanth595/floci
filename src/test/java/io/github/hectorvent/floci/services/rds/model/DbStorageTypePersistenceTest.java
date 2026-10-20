package io.github.hectorvent.floci.services.rds.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DbStorageTypePersistenceTest {
    private static StorageFactory factory(String mode, Path directory) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(600000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("rds")).thenReturn(mode);
        when(access.storageFlushInterval("rds")).thenReturn(600000L);
        return new StorageFactory(config, access);
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void instanceAndSnapshotTypesSurviveQuiescentFactoryReload(String mode, @TempDir Path directory) {
        StorageFactory first = factory(mode, directory);
        StorageBackend<String, DbInstance> instances = first.create("rds", "rds-instances.json",
                new TypeReference<Map<String, DbInstance>>() {});
        StorageBackend<String, DbSnapshot> snapshots = first.create("rds", "rds-snapshots.json",
                new TypeReference<Map<String, DbSnapshot>>() {});
        DbInstance instance = new DbInstance();
        instance.setDbInstanceIdentifier("storage-db");
        instance.setStorageType("gp3");
        DbSnapshot snapshot = new DbSnapshot();
        snapshot.setDbSnapshotIdentifier("storage-snapshot");
        snapshot.setStorageType("gp3");
        try {
            instances.put("us-east-1/storage-db", instance);
            snapshots.put("us-east-1/storage-snapshot", snapshot);
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = factory(mode, directory);
        try {
            StorageBackend<String, DbInstance> restoredInstances = second.create("rds", "rds-instances.json",
                    new TypeReference<Map<String, DbInstance>>() {});
            StorageBackend<String, DbSnapshot> restoredSnapshots = second.create("rds", "rds-snapshots.json",
                    new TypeReference<Map<String, DbSnapshot>>() {});
            assertEquals("gp3", restoredInstances.get("us-east-1/storage-db").orElseThrow().getStorageType());
            assertEquals("gp3", restoredSnapshots.get("us-east-1/storage-snapshot").orElseThrow().getStorageType());
        } finally {
            second.shutdownAll();
        }
    }

    @Test
    void absentAndNullLegacyMembersUseThePriorGp2Fallback() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        for (String json : new String[]{"{}", "{\"storageType\":null}"}) {
            assertEquals("gp2", mapper.readValue(json, DbInstance.class).getStorageType());
            assertEquals("gp2", mapper.readValue(json, DbSnapshot.class).getStorageType());
        }
    }
}
