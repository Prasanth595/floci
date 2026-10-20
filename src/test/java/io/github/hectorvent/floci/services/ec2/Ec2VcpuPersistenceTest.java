package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2VcpuPersistenceTest {
    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void reloadedAdmissionRetainsSpotProvenanceAndUncertainCleanupHolds(String mode, @TempDir Path dir) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.storage().persistentPath()).thenReturn(dir.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        when(config.services().ec2().mock()).thenReturn(true);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        when(config.services().ec2().mockPublicAddress()).thenReturn(Optional.empty());
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(5);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("ec2")).thenReturn(mode);
        when(access.storageFlushInterval("ec2")).thenReturn(60000L);
        StorageFactory first = new StorageFactory(config, access);
        try {
            Ec2Service service = service(config, first);
            Instance spot = guest("i-persisted-spot", InstanceState.running());
            spot.setSpot(true);
            spot.setUserDataAttempted(false);
            Instance held = guest("i-persisted-hold", InstanceState.shuttingDown());
            held.setDockerContainerId("uncertain-workload");
            held.setSshHostPort(2222);
            held.setUserDataAttempted(true);
            Instance legacy = guest("i-persisted-legacy", InstanceState.stopped());
            service.putInstanceForTest(spot);
            service.putInstanceForTest(held);
            service.putInstanceForTest(legacy);
        } finally {
            first.shutdownAll();
        }
        StorageFactory reloaded = new StorageFactory(config, access);
        try {
            Ec2Service service = service(config, reloaded);
            Map<String, Instance> instances = service.describeInstances("us-east-1", List.of(), Map.of()).stream()
                    .flatMap(reservation -> reservation.getInstances().stream())
                    .collect(java.util.stream.Collectors.toMap(Instance::getInstanceId, i -> i));
            Instance held = instances.get("i-persisted-hold");
            assertEquals("shutting-down", held.getState().getName());
            assertEquals("uncertain-workload", held.getDockerContainerId());
            assertEquals(2222, held.getSshHostPort());
            assertTrue(instances.get("i-persisted-spot").isSpot());
            assertEquals(Boolean.FALSE, instances.get("i-persisted-spot").getUserDataAttempted());
            assertEquals(Boolean.TRUE, held.getUserDataAttempted());
            assertNull(instances.get("i-persisted-legacy").getUserDataAttempted());
            assertFalse(instances.get("i-persisted-legacy").claimInitialUserData());
            Reservation admitted = service.runInstances("us-east-1", "ami-test", "t3.large", 1, 2,
                    null, List.of(), null, null, List.of(), null, null);
            assertEquals(1, admitted.getInstances().size(),
                    "the cleanup hold consumes two vCPUs; the persisted Spot guest consumes none");
            AwsException full = assertThrows(AwsException.class, () -> service.runInstances("us-east-1",
                    "ami-test", "t3.large", 1, 1, null, List.of(), null, null, List.of(), null, null));
            assertEquals("VcpuLimitExceeded", full.getErrorCode());
        } finally {
            reloaded.shutdownAll();
        }
    }

    private static Instance guest(String id, InstanceState state) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setInstanceType("t3.large");
        instance.setOwnerId("000000000000");
        instance.setRegion("us-east-1");
        instance.setState(state);
        return instance;
    }

    private static Ec2Service service(EmulatorConfig config, StorageFactory storage) {
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), new Ec2ImageCatalog(), new Ec2InstanceTypeCatalog(), storage);
    }
}
