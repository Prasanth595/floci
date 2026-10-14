package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.CreateAutoScalingGroupRequest;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplate;
import software.amazon.awssdk.services.autoscaling.model.LaunchTemplateSpecification;
import software.amazon.awssdk.services.autoscaling.model.MixedInstancesPolicy;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.InstanceAttributeName;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Auto Scaling launch-template user data")
class AutoScalingTemplateUserDataTest {
    static Stream<Arguments> userData() {
        return Stream.of(false, true).flatMap(mixed -> Stream.of("YQ", "/w",
                "H4sIAAAAAAAC/1NW1E/KzNMvzuBKTc7IV8hIzcnJ5wIAedQ/FxUAAAA=")
                .map(encoded -> Arguments.of(mixed, encoded)));
    }

    @ParameterizedTest
    @MethodSource("userData")
    @DisplayName("Direct and mixed templates preserve original encoded instance user data")
    void originalEncodingReachesLaunchedInstance(boolean mixed, String encoded) throws InterruptedException {
        String name = TestFixtures.uniqueName("asg-template-data");
        try (AutoScalingClient asg = TestFixtures.autoScalingClient(); Ec2Client ec2 = TestFixtures.ec2Client()) {
            ec2.createLaunchTemplate(r -> r.launchTemplateName(name).launchTemplateData(d -> d
                    .imageId("ami-amazonlinux2023").instanceType("t3.micro").userData(encoded)));
            try {
                LaunchTemplateSpecification template = LaunchTemplateSpecification.builder()
                        .launchTemplateName(name).version("1").build();
                CreateAutoScalingGroupRequest.Builder create = CreateAutoScalingGroupRequest.builder()
                        .autoScalingGroupName(name).minSize(0).maxSize(1).desiredCapacity(1)
                        .availabilityZones("us-east-1a");
                if (mixed) {
                    create.mixedInstancesPolicy(MixedInstancesPolicy.builder()
                            .launchTemplate(LaunchTemplate.builder().launchTemplateSpecification(template).build())
                            .build());
                } else {
                    create.launchTemplate(template);
                }
                asg.createAutoScalingGroup(create.build());
                try {
                    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    AutoScalingGroup group = describe(asg, name);
                    while (group.instances().isEmpty() && System.nanoTime() < deadline) {
                        Thread.sleep(250);
                        group = describe(asg, name);
                    }
                    assertThat(group.instances()).hasSize(1);
                    String id = group.instances().get(0).instanceId();
                    assertThat(ec2.describeInstanceAttribute(r -> r.instanceId(id)
                            .attribute(InstanceAttributeName.USER_DATA)).userData().value()).isEqualTo(encoded);
                } finally {
                    asg.deleteAutoScalingGroup(r -> r.autoScalingGroupName(name).forceDelete(true));
                }
            } finally {
                ec2.deleteLaunchTemplate(r -> r.launchTemplateName(name));
            }
        }
    }

    private static AutoScalingGroup describe(AutoScalingClient asg, String name) {
        return asg.describeAutoScalingGroups(r -> r.autoScalingGroupNames(name)).autoScalingGroups().get(0);
    }
}
