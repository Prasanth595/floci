package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MetadataOptions declared through CloudFormation reach the instance: an AWS::EC2::LaunchTemplate
 * stores them, an AWS::EC2::Instance launches with its template's options under its own, and an
 * update changes them in place, as the property's "No interruption" update behavior says. A
 * template with {@code HttpTokens: required} is what CDK's {@code requireImdsv2} generates; the
 * metadata service enforces the stored options ({@code Ec2MetadataTokenRequirementIntegrationTest}).
 */
@QuarkusTest
class CloudFormationEc2MetadataOptionsIntegrationTest {
    private static final String ACCOUNT = "468024680246";
    private static final String REGION = "us-east-1";
    private static final String INSTANCE = "DescribeInstancesResponse.reservationSet.item.instancesSet.item.";

    @Test
    void launchTemplateMetadataOptionsReachTheInstance() {
        String stackName = "cfn-metadata-lt-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "InstanceType": "t3.micro",
                          "MetadataOptions": {
                            "HttpTokens": "required",
                            "HttpPutResponseHopLimit": 2,
                            "InstanceMetadataTags": "enabled"
                          }
                        }
                      }
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "InstanceId": {"Value": {"Ref": "Inst"}},
                    "TemplateId": {"Value": {"Ref": "Lt"}}
                  }
                }
                """;
        try {
            cfn("CreateStack", stackName, template);
            XmlPath stack = describeStack(stackName, "CREATE_COMPLETE");
            String instanceId = output(stack, "InstanceId");

            XmlPath versions = ec2Request("DescribeLaunchTemplateVersions")
                    .formParam("LaunchTemplateId", output(stack, "TemplateId"))
                    .post("/").then().statusCode(200).extract().xmlPath();
            String stored = "DescribeLaunchTemplateVersionsResponse.launchTemplateVersionSet.item"
                    + ".launchTemplateData.metadataOptions.";
            assertEquals("required", versions.getString(stored + "httpTokens"));
            assertEquals("2", versions.getString(stored + "httpPutResponseHopLimit"));
            assertEquals("enabled", versions.getString(stored + "instanceMetadataTags"));

            XmlPath described = describeInstance(instanceId);
            assertEquals("required", described.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("2", described.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));
            assertEquals("enabled", described.getString(INSTANCE + "metadataOptions.instanceMetadataTags"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    @Test
    void instanceMetadataOptionsOverrideItsTemplateAndUpdateInPlace() {
        String stackName = "cfn-metadata-inst-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "MetadataOptions": {"HttpTokens": "optional", "HttpPutResponseHopLimit": "3"}
                        }
                      }
                    },
                    "Inst": {
                      "Type": "AWS::EC2::Instance",
                      "Properties": {
                        "LaunchTemplate": {
                          "LaunchTemplateId": {"Ref": "Lt"},
                          "Version": {"Fn::GetAtt": ["Lt", "LatestVersionNumber"]}
                        },
                        "MetadataOptions": {"HttpTokens": "%s"}
                      }
                    }
                  },
                  "Outputs": {"InstanceId": {"Value": {"Ref": "Inst"}}}
                }
                """;
        try {
            cfn("CreateStack", stackName, template.formatted("required"));
            String instanceId = output(describeStack(stackName, "CREATE_COMPLETE"), "InstanceId");
            XmlPath created = describeInstance(instanceId);
            assertEquals("required", created.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("3", created.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));

            cfn("UpdateStack", stackName, template.formatted("optional"));
            assertEquals(instanceId, output(describeStack(stackName, "UPDATE_COMPLETE"), "InstanceId"));
            XmlPath updated = describeInstance(instanceId);
            assertEquals("optional", updated.getString(INSTANCE + "metadataOptions.httpTokens"));
            assertEquals("3", updated.getString(INSTANCE + "metadataOptions.httpPutResponseHopLimit"));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    @Test
    void aHopLimitThatIsNotAnIntegerFailsTheLaunchTemplate() {
        String stackName = "cfn-metadata-bad-" + UUID.randomUUID().toString().substring(0, 8);
        String template = """
                {
                  "Resources": {
                    "Lt": {
                      "Type": "AWS::EC2::LaunchTemplate",
                      "Properties": {
                        "LaunchTemplateData": {
                          "ImageId": "ami-12345678",
                          "MetadataOptions": {"HttpPutResponseHopLimit": "two"}
                        }
                      }
                    }
                  }
                }
                """;
        try {
            cfn("CreateStack", stackName, template);
            describeStack(stackName, "ROLLBACK_COMPLETE");
            cfnRequest("DescribeStackEvents").formParam("StackName", stackName)
                    .post("/").then().statusCode(200)
                    .body(containsString("HttpPutResponseHopLimit must be an integer."));
        } finally {
            cfn("DeleteStack", stackName, null);
        }
    }

    private static void cfn(String action, String stackName, String template) {
        RequestSpecification request = cfnRequest(action).formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.post("/").then().statusCode(200);
    }

    private static XmlPath describeStack(String stackName, String expectedStatus) {
        return cfnRequest("DescribeStacks").formParam("StackName", stackName)
                .post("/").then().statusCode(200)
                .body(containsString("<StackStatus>" + expectedStatus + "</StackStatus>"))
                .extract().xmlPath();
    }

    private static String output(XmlPath stack, String key) {
        return stack.getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private static XmlPath describeInstance(String instanceId) {
        return ec2Request("DescribeInstances").formParam("InstanceId.1", instanceId)
                .post("/").then().statusCode(200).extract().xmlPath();
    }

    private static RequestSpecification cfnRequest(String action) {
        return request("cloudformation", action);
    }

    private static RequestSpecification ec2Request(String action) {
        return request("ec2", action);
    }

    private static RequestSpecification request(String service, String action) {
        return given().header("Authorization", EnforcementFixtures.auth(ACCOUNT, REGION, service))
                .contentType("application/x-www-form-urlencoded").formParam("Action", action);
    }
}
