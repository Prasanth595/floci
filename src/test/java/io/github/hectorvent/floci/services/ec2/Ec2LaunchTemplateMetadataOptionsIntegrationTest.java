package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingReconciler;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** A launch template's MetadataOptions reach instances launched by EC2 Fleet and Auto Scaling. */
@QuarkusTest
class Ec2LaunchTemplateMetadataOptionsIntegrationTest {
    private static final String OWNER = "212121212121";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Inject
    AutoScalingReconciler reconciler;

    @Inject
    AutoScalingService groups;

    @ParameterizedTest
    @ValueSource(strings = {"fleet", "asg"})
    void templateMetadataOptionsReachTheLaunchedInstance(String source) {
        String name = "template-metadata-" + UUID.randomUUID().toString().substring(0, 8);
        String template = request("ec2", "CreateLaunchTemplate").formParam("LaunchTemplateName", name)
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.MetadataOptions.HttpTokens", "required")
                .formParam("LaunchTemplateData.MetadataOptions.HttpPutResponseHopLimit", "2")
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        String instance = null;
        try {
            if ("fleet".equals(source)) {
                instance = request("ec2", "CreateFleet").formParam("Type", "instant")
                        .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", template)
                        .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                        .formParam("TargetCapacitySpecification.TotalTargetCapacity", 1)
                        .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                        .post("/").then().statusCode(200)
                        .extract().path("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item");
            } else {
                request("autoscaling", "CreateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("LaunchTemplate.LaunchTemplateId", template).formParam("LaunchTemplate.Version", "1")
                        .formParam("MinSize", 0).formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                        .formParam("AvailabilityZones.member.1", "us-east-1a").post("/").then().statusCode(200);
                RequestScopes.runAs(OWNER, () -> reconciler.reconcile(
                        groups.describeAutoScalingGroups("us-east-1", List.of(name)).getFirst()));
                instance = request("autoscaling", "DescribeAutoScalingGroups")
                        .formParam("AutoScalingGroupNames.member.1", name).post("/").then().statusCode(200)
                        .extract().path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult"
                                + ".AutoScalingGroups.member.Instances.member.InstanceId");
            }
            request("ec2", "DescribeInstances").formParam("InstanceId.1", instance).post("/").then().statusCode(200)
                    .body(INSTANCE + "metadataOptions.httpTokens", equalTo("required"))
                    .body(INSTANCE + "metadataOptions.httpPutResponseHopLimit", equalTo("2"));
        } finally {
            // Cleanup ignores the status, so a test that failed before launching keeps its own failure.
            if ("asg".equals(source)) {
                request("autoscaling", "DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("ForceDelete", "true").post("/");
            } else if (instance != null) {
                request("ec2", "TerminateInstances").formParam("InstanceId.1", instance).post("/");
            }
            request("ec2", "DeleteLaunchTemplate").formParam("LaunchTemplateId", template).post("/");
        }
    }

    private static RequestSpecification request(String service, String action) {
        return given().header("Authorization", EnforcementFixtures.auth(OWNER, service)).formParam("Action", action);
    }
}
