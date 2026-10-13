package io.github.hectorvent.floci.services.autoscaling;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class AutoScalingRefreshSuspensionIntegrationTest {
    @Inject
    AutoScalingReconciler reconciler;

    private static final String GROUP = "DescribeAutoScalingGroupsResponse."
            + "DescribeAutoScalingGroupsResult.AutoScalingGroups.member";
    private static final String REFRESH = "DescribeInstanceRefreshesResponse."
            + "DescribeInstanceRefreshesResult.InstanceRefreshes.member";

    @ParameterizedTest
    @ValueSource(strings = {"Launch", "Terminate", "InstanceRefresh", "all"})
    void pausedRefreshKeepsOriginalAndResumesToOneHealthyReplacement(String process) {
        String name = "refresh-pause-" + UUID.randomUUID().toString().substring(0, 8);
        request("CreateLaunchConfiguration").formParam("LaunchConfigurationName", name)
                .formParam("ImageId", "ami-amazonlinux2023").formParam("InstanceType", "t3.micro")
                .post("/").then().statusCode(200);
        try {
            request("CreateAutoScalingGroup").formParam("AutoScalingGroupName", name)
                    .formParam("LaunchConfigurationName", name).formParam("MinSize", 0)
                    .formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                    .formParam("AvailabilityZones.member.1", "us-east-1a")
                    .post("/").then().statusCode(200);
            try {
                reconcileTwice();
                assertEquals("InService", group(name).xmlPath().getString(GROUP + ".Instances.member.LifecycleState"));
                String originalId = group(name).xmlPath().getString(GROUP + ".Instances.member.InstanceId");
                process("SuspendProcesses", name, process);
                String refreshId = request("StartInstanceRefresh").formParam("AutoScalingGroupName", name)
                        .post("/").then().statusCode(200).extract().xmlPath()
                        .getString("StartInstanceRefreshResponse.StartInstanceRefreshResult.InstanceRefreshId");
                reconcileTwice();
                Response paused = group(name);
                assertEquals(1, paused.xmlPath().getInt(GROUP + ".DesiredCapacity"));
                assertEquals(1, paused.xmlPath().getList(GROUP + ".Instances.member").size());
                assertEquals(originalId, paused.xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
                assertEquals("running", instanceState(originalId));
                Response refresh = refresh(name, refreshId);
                assertEquals("InProgress", refresh.xmlPath().getString(REFRESH + ".Status"));
                assertEquals(1, refresh.xmlPath().getInt(REFRESH + ".InstancesToUpdate"));
                process("ResumeProcesses", name, process);
                reconcileTwice();
                assertEquals("Successful", refresh(name, refreshId).xmlPath().getString(REFRESH + ".Status"));
                Response resumed = group(name);
                assertEquals(1, resumed.xmlPath().getList(GROUP + ".Instances.member").size());
                assertNotEquals(originalId, resumed.xmlPath().getString(GROUP + ".Instances.member.InstanceId"));
                assertEquals("InService", resumed.xmlPath().getString(GROUP + ".Instances.member.LifecycleState"));
                assertEquals("terminated", instanceState(originalId));
            } finally {
                request("DeleteAutoScalingGroup").formParam("AutoScalingGroupName", name)
                        .formParam("ForceDelete", true).post("/").then().statusCode(200);
            }
        } finally {
            request("DeleteLaunchConfiguration").formParam("LaunchConfigurationName", name)
                    .post("/").then().statusCode(200);
        }
    }

    private void reconcileTwice() {
        reconciler.reconcileAll();
        reconciler.reconcileAll();
    }

    private static Response group(String name) {
        return request("DescribeAutoScalingGroups").formParam("AutoScalingGroupNames.member.1", name)
                .post("/").then().statusCode(200).extract().response();
    }

    private static Response refresh(String name, String refreshId) {
        return request("DescribeInstanceRefreshes").formParam("AutoScalingGroupName", name)
                .formParam("InstanceRefreshIds.member.1", refreshId)
                .post("/").then().statusCode(200).extract().response();
    }

    private static String instanceState(String id) {
        return given().header("Authorization", auth("ec2"))
                .formParam("Action", "DescribeInstances").formParam("InstanceId.1", id)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getString("DescribeInstancesResponse.reservationSet.item.instancesSet.item.instanceState.name");
    }

    private static void process(String action, String name, String process) {
        RequestSpecification request = request(action).formParam("AutoScalingGroupName", name);
        if (!"all".equals(process)) {
            request.formParam("ScalingProcesses.member.1", process);
        }
        request.post("/").then().statusCode(200);
    }

    private static RequestSpecification request(String action) {
        return given().header("Authorization", auth("autoscaling")).formParam("Action", action);
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=AKID/20260215/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
