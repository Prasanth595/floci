package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.ec2.Ec2Client;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auto Scaling capacity process suspension")
class AutoScalingCapacitySuspensionTest {
    @Test
    @DisplayName("Launch and Terminate suspension retain desired capacity until resumed")
    void desiredCapacityWaitsForResume() throws InterruptedException {
        String name = TestFixtures.uniqueName("sdk-capacity-pause");
        try (AutoScalingClient asg = TestFixtures.autoScalingClient(); Ec2Client ec2 = TestFixtures.ec2Client()) {
            asg.createLaunchConfiguration(r -> r.launchConfigurationName(name)
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro"));
            try {
                asg.createAutoScalingGroup(r -> r.autoScalingGroupName(name).launchConfigurationName(name)
                        .minSize(0).maxSize(1).desiredCapacity(0).availabilityZones("us-east-1a"));
                try {
                    asg.suspendProcesses(r -> r.autoScalingGroupName(name).scalingProcesses("Launch"));
                    asg.setDesiredCapacity(r -> r.autoScalingGroupName(name).desiredCapacity(1));
                    assertPaused(asg, name, 1, 0);
                    asg.resumeProcesses(r -> r.autoScalingGroupName(name).scalingProcesses("Launch"));
                    awaitCapacity(asg, name, 1, 1);
                    String id = group(asg, name).instances().get(0).instanceId();
                    asg.suspendProcesses(r -> r.autoScalingGroupName(name).scalingProcesses("Terminate"));
                    asg.setDesiredCapacity(r -> r.autoScalingGroupName(name).desiredCapacity(0));
                    assertPaused(asg, name, 0, 1);
                    assertThat(ec2.describeInstances(r -> r.instanceIds(id)).reservations().get(0)
                            .instances().get(0).state().nameAsString()).isEqualTo("running");
                    asg.resumeProcesses(r -> r.autoScalingGroupName(name).scalingProcesses("Terminate"));
                    awaitCapacity(asg, name, 0, 0);
                    assertThat(ec2.describeInstances(r -> r.instanceIds(id)).reservations().get(0)
                            .instances().get(0).state().nameAsString()).isEqualTo("terminated");
                } finally {
                    asg.deleteAutoScalingGroup(r -> r.autoScalingGroupName(name).forceDelete(true));
                }
            } finally {
                asg.deleteLaunchConfiguration(r -> r.launchConfigurationName(name));
            }
        }
    }

    private static void assertPaused(AutoScalingClient asg, String name, int desired, int count)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        do {
            assertCapacity(asg, name, desired, count);
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
    }

    private static void awaitCapacity(AutoScalingClient asg, String name, int desired, int count)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (group(asg, name).instances().size() != count && System.nanoTime() < deadline) {
            Thread.sleep(250);
        }
        assertCapacity(asg, name, desired, count);
    }

    private static void assertCapacity(AutoScalingClient asg, String name, int desired, int count) {
        AutoScalingGroup group = group(asg, name);
        assertThat(group.desiredCapacity()).isEqualTo(desired);
        assertThat(group.instances()).hasSize(count);
    }

    private static AutoScalingGroup group(AutoScalingClient asg, String name) {
        return asg.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name)).autoScalingGroups().get(0);
    }
}
