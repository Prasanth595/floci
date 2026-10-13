package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefresh;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.Placement;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingRefreshStorageTest {
    @TempDir Path storagePath;
    private final List<StorageFactory> factories = new ArrayList<>();
    private static final String REGION = "us-east-1";
    private AutoScalingService service;
    private Ec2Service ec2;
    private AutoScalingReconciler reconciler;
    private AutoScalingGroup group;

    private void setUp(String mode) {
        service = storedService(mode);
        service.createAutoScalingGroup(REGION, "suspended-group", null, "lt-original", null, "1", null,
                0, 3, 1, 300, List.of("us-east-1a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        group = service.describeAutoScalingGroups(REGION, List.of("suspended-group")).getFirst();
        AsgInstance existing = new AsgInstance();
        existing.setInstanceId("i-original");
        existing.setLifecycleState("InService");
        existing.setHealthStatus("Healthy");
        existing.setLaunchTemplateId("lt-original");
        existing.setLaunchTemplateVersion("1");
        group.getInstances().add(existing);
        service.saveAutoScalingGroupIfPresent(group);
        ec2 = mock(Ec2Service.class);
        service.ec2Service = ec2;
        reconciler = new AutoScalingReconciler(service, ec2, mock(ElbV2Service.class));
        when(ec2.isInstanceContainerRunning(anyString())).thenReturn(true);
        when(ec2.describeInstances(eq(REGION), anyList(), isNull()))
                .thenReturn(List.of(reservation("i-original")));
        LaunchTemplate template = new LaunchTemplate();
        template.setLaunchTemplateId("lt-original");
        template.setLatestVersionNumber("1");
        template.getData().setImageId("ami-original");
        template.getData().setInstanceType("t3.micro");
        when(ec2.describeLaunchTemplates(eq(REGION), anyList(), anyList(), anyMap())).thenReturn(List.of(template));
        when(ec2.describeLaunchTemplateVersions(eq(REGION), eq("lt-original"), isNull(), anyList()))
                .thenReturn(List.of(template));
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any())).thenReturn(reservation("i-replacement"));
    }

    private AutoScalingService storedService(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.WalConfig wal = mock(EmulatorConfig.WalConfig.class);
        when(config.storage()).thenReturn(storage);
        when(config.defaultAccountId()).thenReturn("000000000000");
        when(storage.persistentPath()).thenReturn(storagePath.toString());
        when(storage.wal()).thenReturn(wal);
        when(wal.compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("autoscaling")).thenReturn(mode);
        when(access.storageFlushInterval("autoscaling")).thenReturn(60000L);
        StorageFactory factory = new StorageFactory(config, access);
        factories.add(factory);
        AutoScalingService created = new AutoScalingService();
        created.regionResolver = new RegionResolver(REGION, "000000000000");
        created.storageFactory = factory;
        created.initializeStorage();
        return created;
    }

    private void restart(String mode) throws IOException {
        AutoScalingGroup previous = group;
        factories.removeFirst().shutdownAll();
        try (Stream<Path> files = Files.list(storagePath)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains("autoscaling-groups")),
                    "No real group snapshot was written");
        }
        service = storedService(mode);
        service.ec2Service = ec2;
        group = service.describeAutoScalingGroups(REGION, List.of("suspended-group")).getFirst();
        assertNotSame(previous, group, "Restart reused the in-memory group");
        reconciler = new AutoScalingReconciler(service, ec2, mock(ElbV2Service.class));
    }

    @AfterEach
    void closeStorage() {
        factories.forEach(StorageFactory::shutdownAll);
    }

    @ParameterizedTest
    @CsvSource({
        "persistent,Launch,true", "persistent,Terminate,true", "persistent,InstanceRefresh,true",
        "persistent,Launch,false", "persistent,Terminate,false", "persistent,InstanceRefresh,false",
        "hybrid,Launch,true", "hybrid,Terminate,true", "hybrid,InstanceRefresh,true",
        "hybrid,Launch,false", "hybrid,Terminate,false", "hybrid,InstanceRefresh,false",
        "wal,Launch,true", "wal,Terminate,true", "wal,InstanceRefresh,true",
        "wal,Launch,false", "wal,Terminate,false", "wal,InstanceRefresh,false"
    })
    void refreshPauseSurvivesRealStorageRestart(String mode, String process, boolean suspendBefore) throws IOException {
        setUp(mode);
        if (suspendBefore) {
            service.suspendProcesses(REGION, "suspended-group", List.of(process));
        }
        InstanceRefresh started = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        if (!suspendBefore) {
            service.suspendProcesses(REGION, "suspended-group", List.of(process));
        }
        restart(mode);
        InstanceRefresh restored = service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst();
        assertNotSame(started, restored);
        assertEquals(started.getInstanceRefreshId(), restored.getInstanceRefreshId());
        assertTrue(group.getSuspendedProcesses().contains(process));
        reconciler.reconcile(group);
        assertAll(
                () -> verify(ec2, never()).terminateInstances(anyString(), anyList()),
                () -> verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(),
                        any(), any(), anyList(), any(), any(), any()),
                () -> assertEquals(List.of("i-original"), group.getInstances().stream()
                        .map(AsgInstance::getInstanceId).toList()),
                () -> assertNotEquals("Successful", service.describeInstanceRefreshes(
                        REGION, "suspended-group", null, null, null).instanceRefreshes().getFirst().getStatus()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void failedReplacementLaunchRemainsPausedAfterRestart(String mode) throws IOException {
        setUp(mode);
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any()))
                .thenThrow(new IllegalStateException("launch unavailable"))
                .thenReturn(reservation("i-replacement"));
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertTrue(group.getInstances().isEmpty());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        restart(mode);
        InstanceRefresh restored = service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst();
        assertEquals("InProgress", restored.getStatus());
        assertEquals(1, restored.getInstancesToUpdate());
        reconciler.reconcile(group);
        assertAll(
                () -> verify(ec2, times(1)).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(),
                        anyList(), any(), any(), anyList(), any(), any(), any()),
                () -> assertTrue(group.getInstances().isEmpty()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void resumedRefreshReplacesOriginalAfterRestart(String mode) throws IOException {
        setUp(mode);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        restart(mode);
        service.resumeProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertEquals(List.of("i-replacement"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void manualTerminationDuringPausedRefreshStillRunsAfterRestart(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        AsgInstance manual = new AsgInstance();
        manual.setInstanceId("i-manual");
        manual.setLifecycleState("InService");
        manual.setHealthStatus("Healthy");
        group.getInstances().add(manual);
        group.setDesiredCapacity(2);
        service.saveAutoScalingGroupIfPresent(group);
        service.terminateInstanceInAutoScalingGroup(REGION, "i-manual", true);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        restart(mode);
        reconciler.reconcile(group);
        assertAll(
                () -> verify(ec2).terminateInstances(REGION, List.of("i-manual")),
                () -> assertEquals(List.of("i-original"), group.getInstances().stream()
                        .map(AsgInstance::getInstanceId).toList()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void manualTerminationStillRunsAfterRestart(String mode) throws IOException {
        setUp(mode);
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.terminateInstanceInAutoScalingGroup(REGION, "i-original", true);
        restart(mode);
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertTrue(group.getInstances().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void launchRetryResumesWithSavedToken(String mode) throws IOException {
        setUp(mode);
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any()))
                .thenThrow(new IllegalStateException("launch unavailable"))
                .thenReturn(reservation("i-replacement"));
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        String token = refresh.getReplacements().getFirst().getLaunchClientToken();
        reconciler.reconcile(group);
        restart(mode);
        reconciler.reconcile(group);
        reconciler.reconcile(group);
        verify(ec2, times(2)).runInstances(eq(REGION), any(), any(), eq(1), eq(1), any(), anyList(), any(),
                eq(token), anyList(), any(), any(), any());
        verify(ec2, times(1)).terminateInstances(REGION, List.of("i-original"));
        assertEquals("Successful", service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void recoversEc2LaunchWhoseMembershipWasNotSaved(String mode) throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        Reservation launched = reservation("i-recovered");
        launched.getInstances().getFirst().setClientToken(refresh.getReplacements().getFirst().getLaunchClientToken());
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of(launched));
        group.getInstances().clear();
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        assertEquals(List.of("i-recovered"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any());
        reconciler.reconcile(group);
        assertEquals(1, group.getInstances().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void explicitTerminationOfSelectedOriginalIsNotPaused(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.terminateInstanceInAutoScalingGroup(REGION, "i-original", true);
        restart(mode);
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertTrue(group.getInstances().isEmpty());
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void pausedRefreshDoesNotBlockIndependentCapacityIncrease(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        group.setDesiredCapacity(2);
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        verify(ec2, never()).terminateInstances(anyString(), anyList());
        verify(ec2).runInstances(eq(REGION), any(), any(), eq(1), eq(1), any(), anyList(), any(), isNull(),
                anyList(), any(), any(), any());
        assertEquals(2, group.getInstances().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void capacityReductionDoesNotLaunchUnneededReplacement(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        group.setDesiredCapacity(0);
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any());
        assertTrue(group.getInstances().isEmpty());
        assertEquals("Successful", service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst().getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void missingOriginalRetainsPausedReplacementReservation(String mode) throws IOException {
        setUp(mode);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        when(ec2.describeInstances(REGION, List.of("i-original"), null)).thenReturn(List.of());
        restart(mode);
        reconciler.reconcile(group);
        assertTrue(group.getInstances().isEmpty());
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any());
        service.resumeProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        restart(mode);
        reconciler.reconcile(group);
        assertEquals(List.of("i-replacement"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void recoveredLaunchAlreadyInGroupDoesNotDuplicateMembership(String mode) throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        Reservation launched = reservation("i-recovered");
        launched.getInstances().getFirst().setClientToken(refresh.getReplacements().getFirst().getLaunchClientToken());
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of(launched));
        group.getInstances().clear();
        AsgInstance member = new AsgInstance();
        member.setInstanceId("i-recovered");
        member.setLifecycleState("Pending");
        member.setHealthStatus("Healthy");
        group.getInstances().add(member);
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        assertEquals(1, group.getInstances().size());
        InstanceRefresh restored = service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst();
        assertEquals("i-recovered", restored.getReplacements().getFirst().getReplacementInstanceId());
        assertEquals("Successful", restored.getStatus());
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"persistent,Launch", "persistent,Terminate", "persistent,InstanceRefresh",
            "hybrid,Launch", "hybrid,Terminate", "hybrid,InstanceRefresh",
            "wal,Launch", "wal,Terminate", "wal,InstanceRefresh"})
    void pausedOriginalKeepsTargetRegistrationUntilResume(String mode, String process) throws IOException {
        setUp(mode);
        group.setTargetGroupARNs(List.of("test-target-group"));
        service.saveAutoScalingGroupIfPresent(group);
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        service.suspendProcesses(REGION, "suspended-group", List.of(process));
        restart(mode);
        ElbV2Service elb = mock(ElbV2Service.class);
        TargetDescription target = new TargetDescription();
        target.setId("i-original");
        TargetHealth health = new TargetHealth();
        health.setTarget(target);
        health.setState("healthy");
        when(elb.describeTargetHealth(REGION, "test-target-group", List.of())).thenReturn(List.of(health));
        reconciler = new AutoScalingReconciler(service, ec2, elb);
        reconciler.reconcile(group);
        verify(elb, never()).deregisterTargets(anyString(), anyString(), anyList());
        verify(ec2, never()).terminateInstances(anyString(), anyList());
        service.resumeProcesses(REGION, "suspended-group", List.of(process));
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        verify(elb, atLeastOnce()).deregisterTargets(eq(REGION), eq("test-target-group"),
                argThat(targets -> targets.stream().anyMatch(value -> "i-original".equals(value.getId()))));
        assertEquals(List.of("i-replacement"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
    }

    @ParameterizedTest
    @CsvSource({"persistent,false,false", "persistent,false,true", "persistent,true,false", "persistent,true,true",
            "hybrid,false,false", "hybrid,false,true", "hybrid,true,false", "hybrid,true,true",
            "wal,false,false", "wal,false,true", "wal,true,false", "wal,true,true"})
    void detachedInstancesAreNotReadoptedAfterRestart(String mode, boolean replacement, boolean decrement)
            throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        String detachedId = replacement ? "i-detached" : "i-original";
        if (replacement) {
            group.getInstances().clear();
            AsgInstance member = new AsgInstance();
            member.setInstanceId(detachedId);
            member.setLifecycleState("InService");
            member.setHealthStatus("Healthy");
            group.getInstances().add(member);
            refresh.getReplacements().getFirst().setReplacementInstanceId(detachedId);
            refresh.getReplacements().getFirst().setPhase("Warming");
            service.saveInstanceRefresh(refresh);
            service.saveAutoScalingGroupIfPresent(group);
            Reservation launched = reservation(detachedId);
            launched.getInstances().getFirst().setClientToken(refresh.getReplacements().getFirst().getLaunchClientToken());
            when(ec2.describeInstances(eq(REGION), any(), isNull())).thenReturn(List.of(launched));
        }
        service.suspendProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        service.detachInstances(REGION, "suspended-group", List.of(detachedId), decrement);
        restart(mode);
        InstanceRefresh restored = service.describeInstanceRefreshes(REGION, "suspended-group", null, null, null)
                .instanceRefreshes().getFirst();
        assertTrue(restored.getReplacements().isEmpty(), "Explicit detach retained refresh ownership");
        service.resumeProcesses(REGION, "suspended-group", List.of("InstanceRefresh"));
        reconciler.reconcile(group);
        reconciler.reconcile(group);
        assertEquals(decrement ? 0 : 1, group.getDesiredCapacity());
        assertEquals(decrement ? 0 : 1, group.getInstances().size());
        assertTrue(group.getInstances().stream().noneMatch(member -> detachedId.equals(member.getInstanceId())));
        verify(ec2, never()).terminateInstances(anyString(), argThat(ids -> ids.contains(detachedId)));
        if (decrement) {
            verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                    any(), anyList(), any(), any(), any());
        }
        assertEquals("Successful", restored.getStatus());
    }

    @ParameterizedTest
    @CsvSource({"persistent,false", "persistent,true", "hybrid,false", "hybrid,true", "wal,false", "wal,true"})
    void recoveredLaunchRetainsAttemptSourceAfterGroupChanges(String mode, boolean sourceDeleted) throws IOException {
        setUp(mode);
        List<Reservation> launched = new ArrayList<>();
        when(ec2.describeInstances(REGION, List.of(), null)).thenAnswer(invocation -> List.copyOf(launched));
        when(ec2.runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(), any(),
                anyList(), any(), any(), any())).thenAnswer(invocation -> {
                    Reservation accepted = reservation("i-accepted");
                    accepted.getInstances().getFirst().setClientToken(invocation.getArgument(8));
                    accepted.getInstances().getFirst().setInstanceType("t3.micro");
                    accepted.getInstances().getFirst().setPlacement(new Placement("us-east-1a"));
                    launched.add(accepted);
                    throw new IllegalStateException("EC2 accepted launch but response was lost");
                });
        service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        reconciler.reconcile(group);
        assertTrue(group.getInstances().isEmpty());
        assertEquals(1, launched.size());
        group.setLaunchTemplateId("lt-changed");
        group.setAvailabilityZones(List.of("us-east-1b"));
        service.saveAutoScalingGroupIfPresent(group);
        LaunchTemplate changed = new LaunchTemplate();
        changed.setLaunchTemplateId("lt-changed");
        changed.setLatestVersionNumber("2");
        changed.getData().setImageId("ami-changed");
        changed.getData().setInstanceType("m5.large");
        when(ec2.describeLaunchTemplates(eq(REGION), anyList(), anyList(), anyMap()))
                .thenReturn(sourceDeleted ? List.of() : List.of(changed));
        when(ec2.describeLaunchTemplateVersions(eq(REGION), eq("lt-changed"), isNull(), anyList()))
                .thenReturn(sourceDeleted ? List.of() : List.of(changed));
        restart(mode);
        reconciler.reconcile(group);
        assertEquals(List.of("i-accepted"), group.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        AsgInstance recovered = group.getInstances().getFirst();
        assertAll(() -> assertEquals("lt-original", recovered.getLaunchTemplateId()),
                () -> assertEquals("1", recovered.getLaunchTemplateVersion()),
                () -> assertEquals("t3.micro", recovered.getInstanceType()),
                () -> assertEquals("us-east-1a", recovered.getAvailabilityZone()));
        verify(ec2, times(1)).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void legacyRecoveredLaunchUsesOnlyKnownEc2Metadata(String mode) throws IOException {
        setUp(mode);
        InstanceRefresh refresh = service.startInstanceRefresh(REGION, "suspended-group", new InstanceRefresh());
        Reservation launched = reservation("i-legacy");
        Instance instance = launched.getInstances().getFirst();
        instance.setClientToken(refresh.getReplacements().getFirst().getLaunchClientToken());
        instance.setInstanceType("m5.large");
        instance.setPlacement(new Placement("us-east-1b"));
        when(ec2.describeInstances(REGION, List.of(), null)).thenReturn(List.of(launched));
        group.getInstances().clear();
        service.saveAutoScalingGroupIfPresent(group);
        restart(mode);
        reconciler.reconcile(group);
        AsgInstance recovered = group.getInstances().getFirst();
        assertAll(() -> assertEquals("i-legacy", recovered.getInstanceId()),
                () -> assertEquals("m5.large", recovered.getInstanceType()),
                () -> assertEquals("us-east-1b", recovered.getAvailabilityZone()),
                () -> assertNull(recovered.getLaunchConfigurationName()),
                () -> assertNull(recovered.getLaunchTemplateId()),
                () -> assertNull(recovered.getLaunchTemplateName()),
                () -> assertNull(recovered.getLaunchTemplateVersion()));
        verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(), any(),
                any(), anyList(), any(), any(), any());
    }

    private static Reservation reservation(String id) {
        Instance instance = new Instance();
        instance.setInstanceId(id);
        instance.setState(InstanceState.running());
        Reservation reservation = new Reservation();
        reservation.setInstances(List.of(instance));
        return reservation;
    }
}
