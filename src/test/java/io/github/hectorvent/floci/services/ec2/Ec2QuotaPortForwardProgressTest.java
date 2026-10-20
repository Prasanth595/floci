package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.portforward.CloseableEc2PortForwards;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Ec2QuotaPortForwardProgressTest {
    @ParameterizedTest
    @ValueSource(strings = {"start", "resize"})
    void aSlowExistingForwardDoesNotBlockAnotherInstancesAdmission(String request) throws Exception {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(config.services().ec2().mock()).thenReturn(false);
        when(config.services().ec2().publishSecurityGroupPorts()).thenReturn(true);
        when(config.network().securityGroupEnforcement().enabled()).thenReturn(false);
        when(config.services().ec2().mockPublicAddresses())
                .thenReturn(EmulatorConfig.Ec2ServiceConfig.MockPublicAddresses.NONE);
        when(config.services().ec2().mockPublicAddress()).thenReturn(Optional.empty());
        when(config.services().servicequotas().standardOnDemandVcpus()).thenReturn(4);
        DockerClient docker = mock(DockerClient.class);
        InspectContainerCmd inspect = mock(InspectContainerCmd.class);
        InspectContainerResponse empty = mock(InspectContainerResponse.class);
        when(docker.inspectContainerCmd("container-busy")).thenReturn(inspect);
        CountDownLatch inspecting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> forwardThread = new AtomicReference<>();
        when(inspect.exec()).thenAnswer(call -> {
            forwardThread.set(Thread.currentThread());
            inspecting.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return empty;
        });
        CloseableEc2PortForwards forwards = new CloseableEc2PortForwards(docker, mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class), mock(PortAllocator.class), config);
        Ec2ContainerManager manager = mock(Ec2ContainerManager.class);
        doAnswer(call -> {
            Instance instance = call.getArgument(0);
            instance.completeStateTransition(call.getArgument(2), InstanceState.running());
            return null;
        }).when(manager).start(any(), any(), anyLong());
        Ec2Service service = new Ec2Service(config, manager, forwards, mock(AmiImageResolver.class),
                mock(Ec2ImageCatalog.class), new Ec2InstanceTypeCatalog(), new MemoryFactory());
        Instance busy = instance("i-busy", InstanceState.running());
        busy.setDockerContainerId("container-busy");
        Instance independent = instance("i-independent", InstanceState.stopped());
        service.putInstanceForTest(busy);
        service.putInstanceForTest(independent);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> sameCaller = new AtomicReference<>();
        boolean progressed = false;
        try {
            forwards.reconcile(busy, Set.of(8080));
            assertTrue(inspecting.await(2, TimeUnit.SECONDS));
            Future<?> same = callers.submit(() -> {
                sameCaller.set(Thread.currentThread());
                if ("resize".equals(request)) {
                    AwsException refused = assertThrows(AwsException.class, () ->
                            service.modifyInstanceAttribute("us-east-1", busy.getInstanceId(), "instanceType", "t3.small"));
                    assertEquals("IncorrectInstanceState", refused.getErrorCode());
                } else {
                    service.startInstances("us-east-1", List.of(busy.getInstanceId()));
                }
            });
            await(() -> same.isDone() || blockedByForward(sameCaller.get(), forwardThread.get(), busy));
            Future<?> other = callers.submit(() ->
                    service.startInstances("us-east-1", List.of(independent.getInstanceId())));
            try {
                other.get(500, TimeUnit.MILLISECONDS);
                progressed = "running".equals(independent.getState().getName());
            } catch (TimeoutException blocked) {
                // Preserve the actual progress outcome, then drain both real callers before asserting.
            }
            release.countDown();
            same.get(2, TimeUnit.SECONDS);
            other.get(2, TimeUnit.SECONDS);
            assertEquals("running", independent.getState().getName());
            assertTrue(progressed, "an existing instance's slow Docker forward must not own global admission");
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            forwards.close();
            await(() -> forwardThread.get() == null || !forwardThread.get().isAlive());
        }
    }

    private static boolean blockedByForward(Thread caller, Thread forward, Instance instance) {
        if (caller == null || forward == null) {
            return false;
        }
        ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(caller.threadId());
        return info != null && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == forward.threadId() && info.getLockInfo() != null
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(instance);
    }

    private static void await(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(ready.getAsBoolean());
    }

    private static Instance instance(String id, InstanceState state) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setInstanceType("t3.micro");
        instance.setRegion("us-east-1");
        instance.setOwnerId("000000000000");
        instance.setState(state);
        return instance;
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
