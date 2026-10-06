package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::EC2::VPC} applies Tags, EnableDnsHostnames/EnableDnsSupport and InstanceTenancy
 * to the VPC it creates, so DescribeVpcs with a {@code tag:Name} filter (what CDK's
 * {@code Vpc.fromLookup} issues) finds it, and an UpdateStack replaces the tag set in place.
 */
@QuarkusTest
class CloudFormationVpcPropertiesIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-3/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-west-3/ec2/aws4_request";
    private static final Pattern VPC_ID = Pattern.compile("<OutputValue>(vpc-[0-9a-f]+)</OutputValue>");

    private static String template(String name, String extraTags) {
        return """
                {
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {
                        "CidrBlock": "10.44.0.0/16",
                        "EnableDnsHostnames": true,
                        "EnableDnsSupport": true,
                        "InstanceTenancy": "dedicated",
                        "Tags": [{"Key": "Name", "Value": "%s"}%s]
                      }
                    }
                  },
                  "Outputs": {
                    "VpcId": {"Value": {"Ref": "Vpc"}},
                    "Acl": {"Value": {"Fn::GetAtt": ["Vpc", "DefaultNetworkAcl"]}},
                    "Assoc": {"Value": {"Fn::GetAtt": ["Vpc", "CidrBlockAssociations"]}}
                  }
                }
                """.formatted(name, extraTags);
    }

    @Test
    void vpcTagsAndAttributesAreAppliedOnCreateAndReplacedOnUpdate() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-vpc-props-" + suffix;
        String name = "lookup-vpc-" + suffix;

        cfn("CreateStack", stackName, template(name, ", {\"Key\": \"team\", \"Value\": \"blue\"}"))
                .statusCode(200);
        String described = describeStacks(stackName, "CREATE_COMPLETE");
        Matcher m = VPC_ID.matcher(described);
        assertTrue(m.find(), "stack output should carry the vpc id: " + described);
        String vpcId = m.group(1);
        assertTrue(described.contains("<OutputValue>acl-"), "DefaultNetworkAcl should resolve: " + described);
        assertTrue(described.contains("<OutputValue>vpc-cidr-assoc-"),
                "CidrBlockAssociations should resolve: " + described);

        given()
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", name)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId))
            .body("DescribeVpcsResponse.vpcSet.item.instanceTenancy", equalTo("dedicated"))
            .body("DescribeVpcsResponse.vpcSet.item.tagSet.item.find { it.key == 'team' }.value", equalTo("blue"));

        given()
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeVpcAttribute")
            .formParam("VpcId", vpcId)
            .formParam("Attribute", "enableDnsHostnames")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcAttributeResponse.enableDnsHostnames.value", equalTo("true"));

        // Update: Name changes, team is dropped. CloudFormation replaces the tag set.
        cfn("UpdateStack", stackName, template(name + "-v2", "")).statusCode(200);
        describeStacks(stackName, "UPDATE_COMPLETE");

        given()
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", name + "-v2")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVpcsResponse.vpcSet.item.vpcId", equalTo(vpcId))
            .body(not(containsString("<key>team</key>")));

        given()
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeVpcs")
            .formParam("Filter.1.Name", "tag:Name")
            .formParam("Filter.1.Value.1", name)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(vpcId)));
    }

    private static ValidatableResponse cfn(String action, String stackName, String template) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then();
    }

    private static String describeStacks(String stackName, String expectedStatus) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
            .extract().asString();
    }
}
