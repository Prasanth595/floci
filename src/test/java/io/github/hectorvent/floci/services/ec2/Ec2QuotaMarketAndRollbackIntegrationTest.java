package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(Ec2QuotaMarketAndRollbackIntegrationTest.TwoVcpus.class)
class Ec2QuotaMarketAndRollbackIntegrationTest {
    public static class TwoVcpus implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.servicequotas.standard-on-demand-vcpus", "2");
        }
    }

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260205/us-east-1/" + service + "/aws4_request";
    }

    @Test
    void directAndTemplateSpotDoNotConsumeOnDemandCapacityAndRequestOverridesTemplate() {
        String account = "687432101234";
        String header = auth(account, "ec2");
        given().header("Authorization", header).formParams("Action", "RunInstances",
                "ImageId", "ami-0abcdef1234567890", "InstanceType", "t3.micro", "MinCount", "1", "MaxCount", "1")
                .post("/").then().statusCode(200);
        try {
            given().header("Authorization", header).formParams("Action", "RunInstances",
                    "ImageId", "ami-0abcdef1234567890", "InstanceType", "t3.micro", "MinCount", "1", "MaxCount", "1",
                    "InstanceMarketOptions.MarketType", "spot")
                    .post("/").then().statusCode(200).body(containsString("<instanceLifecycle>spot</instanceLifecycle>"));
            String template = given().header("Authorization", header).formParams("Action", "CreateLaunchTemplate",
                    "LaunchTemplateName", "quota-market-" + UUID.randomUUID(),
                    "LaunchTemplateData.ImageId", "ami-0abcdef1234567890", "LaunchTemplateData.InstanceType", "t3.micro",
                    "LaunchTemplateData.InstanceMarketOptions.MarketType", "spot")
                    .post("/").then().statusCode(200)
                    .extract().path("CreateLaunchTemplateResponse.launchTemplate.launchTemplateId");
            try {
                given().header("Authorization", header).formParams("Action", "RunInstances",
                        "LaunchTemplate.LaunchTemplateId", template, "MinCount", "1", "MaxCount", "1")
                        .post("/").then().statusCode(200)
                        .body(containsString("<instanceLifecycle>spot</instanceLifecycle>"));
                given().header("Authorization", header).formParams("Action", "RunInstances",
                        "LaunchTemplate.LaunchTemplateId", template, "MinCount", "1", "MaxCount", "1",
                        "InstanceMarketOptions.MarketType", "on-demand")
                        .post("/").then().statusCode(400).body(containsString("VcpuLimitExceeded"));
                given().header("Authorization", header).formParam("Action", "DescribeInstances")
                        .post("/").then().statusCode(200)
                        .body(containsString("<instanceLifecycle>spot</instanceLifecycle>"));
            } finally {
                given().header("Authorization", header).formParams("Action", "DeleteLaunchTemplate",
                        "LaunchTemplateId", template).post("/").then().statusCode(200);
            }
        } finally {
            given().post("/_floci/state/reset").then().statusCode(200);
        }
    }

    @Test
    void refusedResizeCannotReportThatPhysicalRollbackCompleted() {
        String header = auth("687432101235", "cloudformation");
        String name = "quota-resize-" + UUID.randomUUID();
        String template = """
                {"Resources":{"Server":{"Type":"AWS::EC2::Instance","Properties":{
                  "ImageId":"ami-0abcdef1234567890","InstanceType":"%s"}}}}
                """;
        given().header("Authorization", header).formParams("Action", "CreateStack", "StackName", name,
                "TemplateBody", template.formatted("t3.micro")).post("/").then().statusCode(200);
        try {
            given().header("Authorization", header).formParams("Action", "DescribeStacks", "StackName", name)
                    .post("/").then().statusCode(200).body(containsString("CREATE_COMPLETE"));
            given().header("Authorization", header).formParams("Action", "UpdateStack", "StackName", name,
                    "TemplateBody", template.formatted("t3.2xlarge")).post("/").then().statusCode(200);
            given().header("Authorization", header).formParams("Action", "DescribeStacks", "StackName", name)
                    .post("/").then().statusCode(200).body(containsString("UPDATE_ROLLBACK_FAILED"));
            given().header("Authorization", header).formParams("Action", "DescribeStackEvents", "StackName", name)
                    .post("/").then().statusCode(200).body(containsString("vCPU"));
            given().header("Authorization", auth("687432101235", "ec2"))
                    .formParam("Action", "DescribeInstances").post("/").then().statusCode(200)
                    .body(containsString("<instanceType>t3.2xlarge</instanceType>"))
                    .body(containsString("<name>stopped</name>"));
        } finally {
            given().header("Authorization", header).formParams("Action", "DeleteStack", "StackName", name)
                    .post("/").then().statusCode(200);
        }
    }
    @Test
    void aLaterGroupRefusalCannotHideTheAlreadyCompletedResize() {
        String account = "687432101236";
        String header = auth(account, "cloudformation");
        String name = "quota-group-refusal-" + UUID.randomUUID();
        String template = """
                {"Resources":{"Server":{"Type":"AWS::EC2::Instance","Properties":{
                  "ImageId":"ami-0abcdef1234567890","InstanceType":"%s"%s}}}}
                """;
        given().header("Authorization", header).formParams("Action", "CreateStack", "StackName", name,
                "TemplateBody", template.formatted("t3.micro", "")).post("/").then().statusCode(200);
        try {
            given().header("Authorization", header).formParams("Action", "DescribeStacks", "StackName", name)
                    .post("/").then().statusCode(200).body(containsString("CREATE_COMPLETE"));
            String originalId = given().header("Authorization", header)
                    .formParams("Action", "DescribeStackResources", "StackName", name).post("/").then()
                    .statusCode(200).extract()
                    .path("DescribeStackResourcesResponse.DescribeStackResourcesResult.StackResources.member.PhysicalResourceId");
            given().header("Authorization", header).formParams("Action", "UpdateStack", "StackName", name,
                    "TemplateBody", template.formatted("t3.small", ",\"SecurityGroupIds\":[\"sg-fffffffffffffffff\"]"))
                    .post("/").then().statusCode(200);
            given().header("Authorization", header).formParams("Action", "DescribeStacks", "StackName", name)
                    .post("/").then().statusCode(200).body(containsString("UPDATE_ROLLBACK_FAILED"));
            given().header("Authorization", header).formParams("Action", "DescribeStackEvents", "StackName", name)
                    .post("/").then().statusCode(200).body(containsString("does not exist"));
            given().header("Authorization", auth(account, "ec2"))
                    .formParams("Action", "DescribeInstances", "InstanceId.1", originalId)
                    .post("/").then().statusCode(200)
                    .body(containsString("<instanceType>t3.small</instanceType>"))
                    .body(containsString("<name>running</name>"));
        } finally {
            given().header("Authorization", header).formParams("Action", "DeleteStack", "StackName", name)
                    .post("/").then().statusCode(200);
        }
    }

}
