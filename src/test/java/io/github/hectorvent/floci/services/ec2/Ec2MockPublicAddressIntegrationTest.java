package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingReconciler;
import io.github.hectorvent.floci.services.autoscaling.AutoScalingService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class Ec2MockPublicAddressIntegrationTest {
    private static final AtomicLong ACCOUNTS = new AtomicLong(722000000000L);
    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Inject AutoScalingReconciler reconciler;
    @Inject AutoScalingService groups;

    @ParameterizedTest
    @CsvSource({
            "direct,false,true,true", "direct,true,false,false",
            "direct,true,unset,true", "direct,false,unset,false",
            "template,false,true,true", "template,true,false,false",
            "template,true,unset,true", "template,false,unset,false",
            "asg,false,true,true", "asg,true,false,false",
            "asg,true,unset,true", "asg,false,unset,false",
            "fleet,false,true,true", "fleet,true,false,false",
            "fleet,true,unset,true", "fleet,false,unset,false"
    })
    void publicIpUsesTheSelectedLaunchValueAndSurvivesOnlyTheRunningLifetime(
            String source, boolean subnetDefault, String override, boolean publicExpected) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.86.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.86.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", subnetDefault).post("/").then().statusCode(200);
        String image = "ami-amazonlinux2023";
        String template = null;
        if (!"direct".equals(source)) {
            RequestSpecification create = request("CreateLaunchTemplate", owner)
                    .formParam("LaunchTemplateName", "public-address")
                    .formParam("LaunchTemplateData.ImageId", image)
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro");
            if (!"unset".equals(override)) {
                create.formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                        .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress",
                                !Boolean.parseBoolean(override));
            }
            template = create.post("/").then().statusCode(200)
                    .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
            RequestSpecification version = request("CreateLaunchTemplateVersion", owner)
                    .formParam("LaunchTemplateId", template).formParam("SourceVersion", "1")
                    .formParam("LaunchTemplateData.InstanceType", "t3.micro");
            if (!"unset".equals(override)) {
                version.formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                        .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress", override);
            }
            if (!"asg".equals(source) && !"unset".equals(override)) {
                version.formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", subnet);
            }
            version.post("/").then().statusCode(200);
        }
        String instance;
        if ("fleet".equals(source)) {
            RequestSpecification fleet = request("CreateFleet", owner).formParam("Type", "instant")
                    .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", template)
                    .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "2")
                    .formParam("TargetCapacitySpecification.TotalTargetCapacity", 1)
                    .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand");
            if ("unset".equals(override)) {
                fleet.formParam("LaunchTemplateConfig.1.Overrides.1.SubnetId", subnet);
            }
            instance = fleet.post("/").then().statusCode(200)
                    .extract().path("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item");
        } else if ("asg".equals(source)) {
            request("CreateAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("MinSize", 0).formParam("MaxSize", 1).formParam("DesiredCapacity", 1)
                    .formParam("VPCZoneIdentifier", subnet)
                    .formParam("LaunchTemplate.LaunchTemplateId", template).formParam("LaunchTemplate.Version", "2")
                    .post("/").then().statusCode(200);
            RequestScopes.runAs(owner, () -> reconciler.reconcile(
                    groups.describeAutoScalingGroups(REGION, List.of("public-address")).getFirst()));
            instance = request("DescribeAutoScalingGroups", owner).post("/").then().statusCode(200).extract()
                    .path("DescribeAutoScalingGroupsResponse.DescribeAutoScalingGroupsResult.AutoScalingGroups.member.Instances.member.InstanceId");
        } else {
            RequestSpecification launch = request("RunInstances", owner)
                    .formParam("MinCount", 1).formParam("MaxCount", 1);
            if (template != null) {
                launch.formParam("LaunchTemplate.LaunchTemplateId", template).formParam("LaunchTemplate.Version", "2");
                if ("unset".equals(override)) {
                    launch.formParam("SubnetId", subnet);
                }
            } else {
                launch.formParam("ImageId", image).formParam("InstanceType", "t3.micro");
                if (!"unset".equals(override)) {
                    launch.formParam("NetworkInterface.1.DeviceIndex", 0)
                            .formParam("NetworkInterface.1.SubnetId", subnet)
                            .formParam("NetworkInterface.1.AssociatePublicIpAddress", override);
                } else {
                    launch.formParam("SubnetId", subnet);
                }
            }
            instance = launch.post("/").then().statusCode(200)
                    .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        }
        ValidatableResponse first = describe(owner, instance).body(INSTANCE + "subnetId", equalTo(subnet));
        String privateIp = first.extract().path(INSTANCE + "privateIpAddress");
        String publicIp = first.extract().xmlPath().getString(INSTANCE + "ipAddress");
        assertEquals(publicExpected, !publicIp.isEmpty());
        if (publicExpected) {
            assertTrue(publicIp.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"));
        }
        assertEquals(publicIp, describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
        if (!"asg".equals(source)) {
            request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
            assertEquals("", describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
            request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
            ValidatableResponse restarted = describe(owner, instance);
            assertEquals(privateIp, restarted.extract().path(INSTANCE + "privateIpAddress"));
            String replacement = restarted.extract().xmlPath().getString(INSTANCE + "ipAddress");
            assertEquals(publicExpected, !replacement.isEmpty());
            if (publicExpected) {
                assertNotEquals(publicIp, replacement);
            }
        } else {
            request("UpdateAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("DesiredCapacity", 0).post("/").then().statusCode(200);
            request("DeleteAutoScalingGroup", owner).formParam("AutoScalingGroupName", "public-address")
                    .formParam("ForceDelete", true).post("/").then().statusCode(200);
        }
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals("", describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress"));
        if (template != null) {
            request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template).post("/").then().statusCode(200);
        }
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    @Test
    void templateNetworkInterfaceRejectsAnInstanceLevelSubnet() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String template = request("CreateLaunchTemplate", owner)
                .formParam("LaunchTemplateName", "invalid-subnet-combination")
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 0)
                .formParam("LaunchTemplateData.NetworkInterface.1.AssociatePublicIpAddress", true)
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        request("RunInstances", owner).formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("LaunchTemplate.LaunchTemplateId", template)
                .formParam("SubnetId", "subnet-conflicting")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterCombination"));
        request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template)
                .post("/").then().statusCode(200);
    }

    @Test
    void describeInstanceStatusCompletesTheLaunchWithItsAutomaticAddress() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String instance = request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        request("DescribeInstanceStatus", owner).formParam("InstanceId.1", instance).post("/").then()
                .statusCode(200)
                .body("DescribeInstanceStatusResponse.instanceStatusSet.item.instanceState.name", equalTo("running"));

        String publicIp = describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress");
        assertTrue(publicIp.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"), publicIp);
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void elasticIpReplacesTheAutomaticAddressUntilItIsDisassociated(boolean automatic) {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.87.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.87.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        request("ModifySubnetAttribute", owner).formParam("SubnetId", subnet)
                .formParam("MapPublicIpOnLaunch.Value", automatic).post("/").then().statusCode(200);
        String instance = request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("SubnetId", subnet).post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
        String launched = publicIp(owner, instance);
        assertEquals(automatic, !launched.isEmpty());

        ValidatableResponse allocated = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String allocation = allocated.extract().path("AllocateAddressResponse.allocationId");
        String elastic = allocated.extract().path("AllocateAddressResponse.publicIp");
        String association = request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200)
                .extract().path("AssociateAddressResponse.associationId");
        assertEquals(elastic, publicIp(owner, instance));
        request("StopInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals(elastic, publicIp(owner, instance));
        request("StartInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals(elastic, publicIp(owner, instance));

        request("DisassociateAddress", owner).formParam("AssociationId", association).post("/").then()
                .statusCode(200);
        String replacement = publicIp(owner, instance);
        assertEquals(automatic, !replacement.isEmpty());
        assertNotEquals(elastic, replacement);

        request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200);
        // A second address associated with the same instance replaces the first, which stays
        // allocated, so a later DescribeAddresses cannot flip the instance back to it.
        ValidatableResponse second = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String secondAllocation = second.extract().path("AllocateAddressResponse.allocationId");
        String secondElastic = second.extract().path("AllocateAddressResponse.publicIp");
        request("AssociateAddress", owner).formParam("AllocationId", secondAllocation)
                .formParam("InstanceId", instance).post("/").then().statusCode(200);
        request("DescribeAddresses", owner).formParam("AllocationId.1", allocation).post("/").then()
                .statusCode(200)
                .body("DescribeAddressesResponse.addressesSet.item.associationId.size()", equalTo(0));
        assertEquals(secondElastic, publicIp(owner, instance));

        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        assertEquals("", publicIp(owner, instance));
        // Whatever the association answers, a terminated instance takes no public address.
        String lateAssociation = request("AssociateAddress", owner).formParam("AllocationId", allocation)
                .formParam("InstanceId", instance).post("/").then()
                .extract().path("AssociateAddressResponse.associationId");
        assertEquals("", publicIp(owner, instance));
        if (lateAssociation != null) {
            request("DisassociateAddress", owner).formParam("AssociationId", lateAssociation).post("/").then()
                    .statusCode(200);
        }
        request("ReleaseAddress", owner).formParam("AllocationId", allocation).post("/").then().statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", secondAllocation).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    @Test
    void secondAddressDisplacesTheOneARetainedInterfaceCarried() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.89.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String subnet = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.89.1.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        String eni = request("CreateNetworkInterface", owner).formParam("SubnetId", subnet)
                .post("/").then().statusCode(200)
                .extract().path("CreateNetworkInterfaceResponse.networkInterface.networkInterfaceId");
        String first = launchOnInterface(owner, eni);
        ValidatableResponse carried = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String carriedAllocation = carried.extract().path("AllocateAddressResponse.allocationId");
        request("AssociateAddress", owner).formParam("AllocationId", carriedAllocation)
                .formParam("InstanceId", first).post("/").then().statusCode(200);
        // The interface outlives the instance and keeps the address associated with it.
        request("TerminateInstances", owner).formParam("InstanceId.1", first).post("/").then().statusCode(200);

        String second = launchOnInterface(owner, eni);
        ValidatableResponse replacement = request("AllocateAddress", owner).formParam("Domain", "vpc")
                .post("/").then().statusCode(200);
        String replacementAllocation = replacement.extract().path("AllocateAddressResponse.allocationId");
        String replacementIp = replacement.extract().path("AllocateAddressResponse.publicIp");
        String replacementAssociation = request("AssociateAddress", owner)
                .formParam("AllocationId", replacementAllocation).formParam("InstanceId", second)
                .post("/").then().statusCode(200).extract().path("AssociateAddressResponse.associationId");

        request("DescribeAddresses", owner).formParam("AllocationId.1", carriedAllocation).post("/").then()
                .statusCode(200)
                .body("DescribeAddressesResponse.addressesSet.item.associationId.size()", equalTo(0));
        assertEquals(replacementIp, publicIp(owner, second));

        request("TerminateInstances", owner).formParam("InstanceId.1", second).post("/").then().statusCode(200);
        request("DisassociateAddress", owner).formParam("AssociationId", replacementAssociation).post("/").then()
                .statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", replacementAllocation).post("/").then()
                .statusCode(200);
        request("ReleaseAddress", owner).formParam("AllocationId", carriedAllocation).post("/").then().statusCode(200);
        request("DeleteNetworkInterface", owner).formParam("NetworkInterfaceId", eni).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", subnet).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    private String launchOnInterface(String owner, String networkInterface) {
        return request("RunInstances", owner).formParam("ImageId", "ami-amazonlinux2023")
                .formParam("InstanceType", "t3.micro").formParam("MinCount", 1).formParam("MaxCount", 1)
                .formParam("NetworkInterface.1.DeviceIndex", 0)
                .formParam("NetworkInterface.1.NetworkInterfaceId", networkInterface)
                .post("/").then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");
    }

    @Test
    void fleetLaunchesIntoThePrimaryInterfaceSubnetWhateverTheInterfaceOrder() {
        String owner = Long.toString(ACCOUNTS.incrementAndGet());
        String vpc = request("CreateVpc", owner).formParam("CidrBlock", "10.88.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String secondary = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.88.1.0/24").formParam("AvailabilityZone", REGION + "b")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        String primary = request("CreateSubnet", owner).formParam("VpcId", vpc)
                .formParam("CidrBlock", "10.88.2.0/24").formParam("AvailabilityZone", REGION + "a")
                .post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
        String template = request("CreateLaunchTemplate", owner).formParam("LaunchTemplateName", "fleet-primary")
                .formParam("LaunchTemplateData.ImageId", "ami-amazonlinux2023")
                .formParam("LaunchTemplateData.InstanceType", "t3.micro")
                .formParam("LaunchTemplateData.NetworkInterface.1.DeviceIndex", 1)
                .formParam("LaunchTemplateData.NetworkInterface.1.SubnetId", secondary)
                .formParam("LaunchTemplateData.NetworkInterface.2.DeviceIndex", 0)
                .formParam("LaunchTemplateData.NetworkInterface.2.SubnetId", primary)
                .formParam("LaunchTemplateData.NetworkInterface.2.AssociatePublicIpAddress", true)
                .post("/").then().statusCode(200)
                .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
        String instance = request("CreateFleet", owner).formParam("Type", "instant")
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.LaunchTemplateId", template)
                .formParam("LaunchTemplateConfig.1.LaunchTemplateSpecification.Version", "1")
                .formParam("TargetCapacitySpecification.TotalTargetCapacity", 1)
                .formParam("TargetCapacitySpecification.DefaultTargetCapacityType", "on-demand")
                .post("/").then().statusCode(200)
                .extract().path("CreateFleetResponse.fleetInstanceSet.item.instanceIds.item");

        describe(owner, instance).body(INSTANCE + "subnetId", equalTo(primary))
                .body(INSTANCE + "placement.availabilityZone", equalTo(REGION + "a"));
        assertTrue(publicIp(owner, instance).matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"));
        request("TerminateInstances", owner).formParam("InstanceId.1", instance).post("/").then().statusCode(200);
        request("DeleteLaunchTemplate", owner).formParam("LaunchTemplateId", template).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", primary).post("/").then().statusCode(200);
        request("DeleteSubnet", owner).formParam("SubnetId", secondary).post("/").then().statusCode(200);
        request("DeleteVpc", owner).formParam("VpcId", vpc).post("/").then().statusCode(200);
    }

    private String publicIp(String owner, String instance) {
        return describe(owner, instance).extract().xmlPath().getString(INSTANCE + "ipAddress");
    }

    private ValidatableResponse describe(String owner, String instance) {
        return request("DescribeInstances", owner).formParam("InstanceId.1", instance)
                .post("/").then().statusCode(200);
    }

    private RequestSpecification request(String action, String owner) {
        String service = action.contains("AutoScaling") ? "autoscaling" : "ec2";
        return given().formParam("Action", action).header("Authorization", EnforcementFixtures.auth(owner, service));
    }
}
