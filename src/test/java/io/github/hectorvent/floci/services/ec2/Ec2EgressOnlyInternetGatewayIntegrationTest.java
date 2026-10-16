package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The calls aws_egress_only_internet_gateway makes: create with a VPC and TagSpecification, read
 * back by ID, tag, delete. Every read goes through a separate DescribeEgressOnlyInternetGateways
 * rather than trusting the create response.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Ec2EgressOnlyInternetGatewayIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String ITEM =
            "DescribeEgressOnlyInternetGatewaysResponse.egressOnlyInternetGatewaySet.item";

    private static String vpcId;
    private static String eigwId;

    private static RequestSpecification ec2() {
        return given().header("Authorization", AUTH_HEADER);
    }

    private static ValidatableResponse describeById() {
        return ec2()
            .formParam("Action", "DescribeEgressOnlyInternetGateways")
            .formParam("EgressOnlyInternetGatewayId.1", eigwId)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(1)
    void createAttachesTheGatewayToTheVpcAndAppliesTags() {
        vpcId = ec2()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "198.51.100.0/24")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");

        String created = "CreateEgressOnlyInternetGatewayResponse.egressOnlyInternetGateway";
        eigwId = ec2()
            .formParam("Action", "CreateEgressOnlyInternetGateway")
            .formParam("VpcId", vpcId)
            .formParam("TagSpecification.1.ResourceType", "egress-only-internet-gateway")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "ipv6-egress")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(created + ".egressOnlyInternetGatewayId", matchesPattern("eigw-[0-9a-f]{17}"))
            .body(created + ".attachmentSet.item.vpcId", equalTo(vpcId))
            .body(created + ".attachmentSet.item.state", equalTo("attached"))
            .extract().path(created + ".egressOnlyInternetGatewayId");
    }

    @Test
    @Order(2)
    void describeReturnsTheStoredGateway() {
        describeById()
            .body(ITEM + ".size()", equalTo(1))
            .body(ITEM + ".egressOnlyInternetGatewayId", equalTo(eigwId))
            .body(ITEM + ".attachmentSet.item.vpcId", equalTo(vpcId))
            .body(ITEM + ".attachmentSet.item.state", equalTo("attached"))
            .body(ITEM + ".tagSet.item.find { it.key == 'Name' }.value", equalTo("ipv6-egress"));

        ec2()
            .formParam("Action", "DescribeEgressOnlyInternetGateways")
            .formParam("Filter.1.Name", "attachment.vpc-id")
            .formParam("Filter.1.Value.1", vpcId)
            .formParam("Filter.2.Name", "tag:Name")
            .formParam("Filter.2.Value.1", "ipv6-egress")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(ITEM + ".size()", equalTo(1))
            .body(ITEM + ".egressOnlyInternetGatewayId", equalTo(eigwId));

        ec2()
            .formParam("Action", "DescribeEgressOnlyInternetGateways")
            .formParam("Filter.1.Name", "attachment.vpc-id")
            .formParam("Filter.1.Value.1", "vpc-00000000000000000")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(ITEM + ".size()", equalTo(0));
    }

    @Test
    @Order(3)
    void createTagsReachesTheGateway() {
        ec2()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", eigwId)
            .formParam("Tag.1.Key", "Owner")
            .formParam("Tag.1.Value", "TeamA")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        describeById()
            .body(ITEM + ".tagSet.item.find { it.key == 'Owner' }.value", equalTo("TeamA"));
    }

    @Test
    @Order(4)
    void theVpcCannotBeDeletedWhileTheGatewayIsAttached() {
        ec2()
            .formParam("Action", "DeleteVpc")
            .formParam("VpcId", vpcId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("DependencyViolation"));
    }

    @Test
    @Order(5)
    void createRejectsAnUnknownVpc() {
        ec2()
            .formParam("Action", "CreateEgressOnlyInternetGateway")
            .formParam("VpcId", "vpc-00000000000000000")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidVpcID.NotFound"));
    }

    @Test
    @Order(6)
    void deleteRemovesTheGateway() {
        ec2()
            .formParam("Action", "DeleteEgressOnlyInternetGateway")
            .formParam("EgressOnlyInternetGatewayId", eigwId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteEgressOnlyInternetGatewayResponse.returnCode", equalTo("true"));

        describeById().body(ITEM + ".size()", equalTo(0));

        ec2()
            .formParam("Action", "DeleteEgressOnlyInternetGateway")
            .formParam("EgressOnlyInternetGatewayId", eigwId)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("InvalidGatewayID.NotFound"));

        ec2()
            .formParam("Action", "DeleteVpc")
            .formParam("VpcId", vpcId)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(7)
    void aRetriedCreateWithTheSameClientTokenReturnsTheFirstGateway() {
        String vpc = createVpc("198.51.100.0/25");
        String first = createWithToken(vpc, "eigw-retry-token")
            .statusCode(200)
            .extract().path("CreateEgressOnlyInternetGatewayResponse.egressOnlyInternetGateway"
                + ".egressOnlyInternetGatewayId");
        String second = createWithToken(vpc, "eigw-retry-token")
            .statusCode(200)
            .extract().path("CreateEgressOnlyInternetGatewayResponse.egressOnlyInternetGateway"
                + ".egressOnlyInternetGatewayId");
        assertEquals(first, second);

        ec2()
            .formParam("Action", "DescribeEgressOnlyInternetGateways")
            .formParam("Filter.1.Name", "attachment.vpc-id")
            .formParam("Filter.1.Value.1", vpc)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(ITEM + ".size()", equalTo(1))
            .body(ITEM + ".egressOnlyInternetGatewayId", equalTo(first));

        String otherVpc = createVpc("198.51.100.128/25");
        createWithToken(otherVpc, "eigw-retry-token")
            .statusCode(400)
            .body("Response.Errors.Error.Code", equalTo("IdempotentParameterMismatch"));
    }

    private static String createVpc(String cidr) {
        return ec2()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", cidr)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    private static ValidatableResponse createWithToken(String vpc, String token) {
        return ec2()
            .formParam("Action", "CreateEgressOnlyInternetGateway")
            .formParam("VpcId", vpc)
            .formParam("ClientToken", token)
        .when()
            .post("/")
        .then();
    }
}
