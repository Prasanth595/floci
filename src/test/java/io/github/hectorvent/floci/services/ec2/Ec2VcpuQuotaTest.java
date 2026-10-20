package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2VcpuQuotaTest {
    @Test
    void separateLaunchesCannotExceedTheAppliedLimitAndCanRetryAfterTermination() {
        Ec2Service service = service(4);
        Instance first = launch(service, "us-east-1");
        launch(service, "us-east-1");
        AwsException failure = assertThrows(AwsException.class, () -> launch(service, "us-east-1"));
        assertEquals("VcpuLimitExceeded", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertEquals(2, service.describeInstances("us-east-1", List.of(), Map.of()).stream()
                .mapToInt(r -> r.getInstances().size()).sum());
        service.terminateInstances("us-east-1", List.of(first.getInstanceId()));
        assertNotNull(launch(service, "us-east-1"));
    }

    @Test
    void refusedStartPreservesStoppedStateAndCanRetryAfterCapacityIsReleased() {
        Ec2Service service = service(2);
        Instance stopped = launch(service, "us-east-1");
        service.stopInstances("us-east-1", List.of(stopped.getInstanceId()));
        Instance occupied = launch(service, "us-east-1");
        AwsException failure = assertThrows(AwsException.class,
                () -> service.startInstances("us-east-1", List.of(stopped.getInstanceId())));
        assertEquals("VcpuLimitExceeded", failure.getErrorCode());
        assertEquals("stopped", stopped.getState().getName());
        service.terminateInstances("us-east-1", List.of(occupied.getInstanceId()));
        service.startInstances("us-east-1", List.of(stopped.getInstanceId()));
        assertEquals("running", stopped.getState().getName());
    }

    @Test
    void aRejectedBatchDispatchReleasesEveryUndispatchedReservationAndCanRetry() {
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service service = service(4, false, manager);
        Instance first = stopped("i-first");
        Instance second = stopped("i-second");
        service.putInstanceForTest(first);
        service.putInstanceForTest(second);
        doThrow(new RejectedExecutionException("controlled rejection"))
                .when(manager).start(eq(first), any(), anyLong());
        assertThrows(RejectedExecutionException.class,
                () -> service.startInstances("us-east-1", List.of("i-first", "i-second")));
        assertAll(() -> assertEquals("stopped", first.getState().getName()),
                () -> assertEquals("stopped", second.getState().getName()));
        doAnswer(call -> {
            Instance instance = call.getArgument(0);
            instance.completeStateTransition(call.getArgument(2), InstanceState.running());
            return null;
        }).when(manager).start(any(), any(), anyLong());
        service.startInstances("us-east-1", List.of("i-first", "i-second"));
        assertEquals("running", first.getState().getName());
        assertEquals("running", second.getState().getName());
    }

    @Test
    void aPrimaryInterfaceCannotBeSharedByAnAdmittedMaxCountBatch() {
        Ec2Service service = service(4);
        String vpc = service.createVpc("us-east-1", "10.90.0.0/16", false).getVpcId();
        String subnet = service.createSubnet("us-east-1", vpc, "10.90.1.0/24", "us-east-1a").getSubnetId();
        NetworkInterface eni = service.createNetworkInterface("us-east-1", subnet, "single launch",
                null, List.of(), List.of(), List.of());
        AwsException error = assertThrows(AwsException.class, () -> service.runInstances("us-east-1", "ami-test",
                "t3.micro", 1, 2, null, List.of(), subnet, null, List.of(), null, null,
                null, eni.getNetworkInterfaceId(), 0));
        assertEquals("InvalidParameterCombination", error.getErrorCode());
        assertNull(eni.getAttachment());
        assertTrue(service.describeInstances("us-east-1", List.of(), Map.of()).isEmpty());
        assertEquals(1, service.runInstances("us-east-1", "ami-test", "t3.micro", 1, 1, null,
                List.of(), subnet, null, List.of(), null, null, null, eni.getNetworkInterfaceId(), 0)
                .getInstances().size());
    }

    @Test
    void aCapturedTypeOutsideTheFullMetadataCatalogStillConsumesItsDefaultVcpus() {
        Ec2Service service = service(2);
        Instance first = launchType(service, "t3.large");
        AwsException failure = assertThrows(AwsException.class, () -> launchType(service, "t3.large"));
        assertEquals("VcpuLimitExceeded", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertEquals(1, service.describeInstances("us-east-1", List.of(), Map.of()).stream()
                .mapToInt(reservation -> reservation.getInstances().size()).sum());
        service.terminateInstances("us-east-1", List.of(first.getInstanceId()));
        assertNotNull(launchType(service, "t3.large"));
    }

    @Test
    void anUnsizedStandardTypeIsRefusedBeforePublication() {
        Ec2Service service = service(4);
        AwsException failure = assertThrows(AwsException.class, () -> launchType(service, "m999.unmeasured"));
        assertEquals("InvalidParameterValue", failure.getErrorCode());
        assertEquals(400, failure.getHttpStatus());
        assertTrue(service.describeInstances("us-east-1", List.of(), Map.of()).isEmpty());
        assertNotNull(launchType(service, "t3.micro"));
    }

    @Test
    void anUnsizedLegacyGuestCannotBeCountedAsZeroButCanStillBeStoppedAndRemoved() {
        Ec2Service service = service(4);
        Instance legacy = stopped("i-unsized-legacy");
        legacy.setInstanceType("m999.unmeasured");
        legacy.setState(InstanceState.running());
        service.putInstanceForTest(legacy);
        AwsException failure = assertThrows(AwsException.class, () -> launchType(service, "t3.micro"));
        assertEquals("InvalidParameterValue", failure.getErrorCode());
        assertEquals("running", legacy.getState().getName());
        service.stopInstances("us-east-1", List.of(legacy.getInstanceId()));
        Instance valid = launchType(service, "t3.micro");
        AwsException restart = assertThrows(AwsException.class,
                () -> service.startInstances("us-east-1", List.of(legacy.getInstanceId())));
        assertEquals("InvalidParameterValue", restart.getErrorCode());
        assertEquals("stopped", legacy.getState().getName());
        service.terminateInstances("us-east-1", List.of(legacy.getInstanceId(), valid.getInstanceId()));
        assertNotNull(launchType(service, "t3.micro"));
    }

    @Test
    void aPendingStartReservesCapacityBeforeItsWorkerRunsAndCompetingRunCanRetry() throws Exception {
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        Ec2Service service = service(2, false, manager);
        Instance resumed = stopped("i-competing-start");
        service.putInstanceForTest(resumed);
        CountDownLatch accepted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            accepted.countDown();
            assertTrue(release.await(3, TimeUnit.SECONDS));
            resumed.completeStateTransition(call.getArgument(2), InstanceState.running());
            return null;
        }).when(manager).start(eq(resumed), any(), anyLong());
        ExecutorService request = Executors.newSingleThreadExecutor();
        try {
            Future<?> start = request.submit(() -> service.startInstances("us-east-1", List.of(resumed.getInstanceId())));
            assertTrue(accepted.await(2, TimeUnit.SECONDS));
            assertEquals("pending", resumed.getState().getName());
            AwsException competing = assertThrows(AwsException.class, () -> launch(service, "us-east-1"));
            assertEquals("VcpuLimitExceeded", competing.getErrorCode());
            assertEquals(1, service.describeInstances("us-east-1", List.of(), Map.of()).stream()
                    .mapToInt(reservation -> reservation.getInstances().size()).sum());
            release.countDown();
            start.get(2, TimeUnit.SECONDS);
            assertEquals("running", resumed.getState().getName());
            doAnswer(call -> {
                resumed.setState(InstanceState.terminated());
                return null;
            }).when(manager).terminate(eq(resumed), any());
            service.terminateInstances("us-east-1", List.of(resumed.getInstanceId()));
            assertNotNull(launch(service, "us-east-1"));
        } finally {
            release.countDown();
            request.shutdownNow();
            assertTrue(request.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void competingLaunchesCannotPublishMoreThanTheAvailableCapacity() throws Exception {
        Ec2Service service = service(34);
        ExecutorService requests = Executors.newFixedThreadPool(24);
        CountDownLatch ready = new CountDownLatch(24);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 24; i++) {
                outcomes.add(requests.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try {
                        launch(service, "us-east-1");
                        return true;
                    } catch (AwsException refusal) {
                        assertEquals("VcpuLimitExceeded", refusal.getErrorCode());
                        assertEquals(400, refusal.getHttpStatus());
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> outcome : outcomes) {
                if (outcome.get(10, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(17, accepted);
            assertEquals(7, outcomes.size() - accepted);
            assertEquals(17, service.describeInstances("us-east-1", List.of(), Map.of()).stream()
                    .mapToInt(reservation -> reservation.getInstances().size()).sum());
        } finally {
            start.countDown();
            requests.shutdownNow();
            assertTrue(requests.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static Instance launchType(Ec2Service service, String type) {
        return service.runInstances("us-east-1", "ami-test", type, 1, 1, null,
                List.of(), null, null, List.of(), null, null).getInstances().getFirst();
    }

    private static Instance stopped(String id) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setRegion("us-east-1");
        instance.setOwnerId("000000000000");
        instance.setInstanceType("t3.micro");
        instance.setState(InstanceState.stopped());
        return instance;
    }

    private static Instance launch(Ec2Service service, String region) {
        return service.runInstances(region, "ami-test", "t3.micro", 1, 1, null,
                List.of(), null, null, List.of(), null, null).getInstances().getFirst();
    }

    private static Ec2Service service(int limit) {
        return service(limit, true, mock(Ec2ContainerManager.class));
    }

    private static Ec2Service service(int limit, boolean mockMode, Ec2ContainerManager manager) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services().ec2().mock()).thenReturn(mockMode);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        when(config.services().ec2().mockPublicAddress()).thenReturn(Optional.empty());
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(limit);
        return new Ec2Service(config, manager, mock(Ec2PortForwardManager.class),
                mock(AmiImageResolver.class), mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(),
                new MemoryFactory());
    }

    private static final class MemoryFactory extends StorageFactory {
        private MemoryFactory() { super(null, null); }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String service, String file,
                                                        TypeReference<Map<String, V>> type) {
            return AccountAwareStorageBackend.inMemory("000000000000");
        }
    }
}
