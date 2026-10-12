package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingCapacitySuspensionTest {
    private static final String REGION = "us-east-1";
    private AutoScalingService service;
    private Ec2Service ec2;
    private AutoScalingReconciler reconciler;
    private AutoScalingGroup group;

    @BeforeEach
    void setUp() {
        service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, "000000000000");
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

    @ParameterizedTest
    @CsvSource({"Launch,2,true", "Terminate,0,true", "ALL,2,true", "ALL,0,true",
            "Launch,0,false", "Terminate,2,false", "HealthCheck,2,false", "HealthCheck,0,false"})
    void suspensionPausesOnlyItsCapacityDirectionAndResumeContinues(String process, int desired, boolean paused) {
        group.setDesiredCapacity(desired);
        List<String> processes = "ALL".equals(process) ? List.of() : List.of(process);
        service.suspendProcesses(REGION, "suspended-group", processes);
        reconciler.reconcile(group);
        assertEquals(desired, group.getDesiredCapacity());
        if (paused) {
            verify(ec2, never()).terminateInstances(anyString(), anyList());
            verify(ec2, never()).runInstances(eq(REGION), any(), any(), anyInt(), anyInt(), any(), anyList(),
                    any(), any(), anyList(), any(), any(), any());
            assertEquals(List.of("i-original"), group.getInstances().stream()
                    .map(AsgInstance::getInstanceId).toList());
            service.resumeProcesses(REGION, "suspended-group", processes);
            reconciler.reconcile(group);
        }
        assertEquals(desired, group.getInstances().size());
        if (desired == 0) {
            verify(ec2).terminateInstances(REGION, List.of("i-original"));
        } else {
            verify(ec2).runInstances(eq(REGION), any(), any(), eq(1), eq(1), any(), anyList(),
                    any(), any(), anyList(), any(), any(), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Terminate", "InstanceRefresh"})
    void suspensionDoesNotBlockExplicitManualTermination(String process) {
        service.suspendProcesses(REGION, "suspended-group", List.of(process));
        service.terminateInstanceInAutoScalingGroup(REGION, "i-original", true);
        reconciler.reconcile(group);
        verify(ec2).terminateInstances(REGION, List.of("i-original"));
        assertTrue(group.getInstances().isEmpty());
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
