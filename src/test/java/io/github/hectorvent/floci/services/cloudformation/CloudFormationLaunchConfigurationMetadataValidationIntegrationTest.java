package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Launch-configuration MetadataOptions outside the documented values (HttpTokens optional or
 * required, HttpEndpoint enabled or disabled, HttpPutResponseHopLimit an integer from 1 to 64) fail
 * the resource and roll the stack back without leaving a launch configuration behind.
 */
@QuarkusTest
class CloudFormationLaunchConfigurationMetadataValidationIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=222222222222/20261016/us-east-1/cloudformation/aws4_request";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"HttpTokens\":\"invalid\"|Value (invalid) for parameter HttpTokens is invalid. Valid values are: optional, required.",
            "\"HttpEndpoint\":\"invalid\"|Value (invalid) for parameter HttpEndpoint is invalid. Valid values are: enabled, disabled.",
            "\"HttpPutResponseHopLimit\":0|Value (0) for parameter HttpPutResponseHopLimit is invalid. Valid values are between 1 and 64.",
            "\"HttpPutResponseHopLimit\":65|Value (65) for parameter HttpPutResponseHopLimit is invalid. Valid values are between 1 and 64.",
            "\"HttpPutResponseHopLimit\":\"not-an-integer\"|HttpPutResponseHopLimit must be an integer."})
    void invalidMetadataOptionsFailTheResourceWithoutCreatingIt(String member, String message) {
        String name = "cfn-invalid-metadata-" + UUID.randomUUID();
        String template = """
                {"Resources":{"Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration",
                "Properties":{"LaunchConfigurationName":"%s","ImageId":"ami-amazonlinux2023",
                "InstanceType":"t3.micro","MetadataOptions":{%s}}}}}
                """.formatted(name, member);
        given().header("Authorization", AUTH).formParam("Action", "CreateStack")
                .formParam("StackName", name).formParam("TemplateBody", template)
                .post("/").then().statusCode(200);

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name, AUTH);
        assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
        String events = given().header("Authorization", AUTH).formParam("Action", "DescribeStackEvents")
                .formParam("StackName", name).post("/").then().statusCode(200).extract().asString();
        List<String> failures = XmlParser.extractGroups(events, "member").stream()
                .filter(event -> "Launch".equals(event.get("LogicalResourceId"))
                        && "CREATE_FAILED".equals(event.get("ResourceStatus")))
                .map(event -> event.get("ResourceStatusReason"))
                .toList();
        assertThat(failures, contains(equalTo(message)));
        given().header("Authorization", AUTH.replace("/cloudformation/", "/autoscaling/"))
                .formParam("Action", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                        + ".LaunchConfigurations.member.size()", equalTo(0));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"1|1", "64|64", "\"2\"|2", "{\"Ref\":\"Hops\"}|5"})
    void hopLimitsAtTheBoundsAsStringsOrFromParametersAreAccepted(String hopLimit, String stored) {
        String name = "cfn-valid-metadata-" + UUID.randomUUID();
        String template = """
                {"Parameters":{"Hops":{"Type":"Number","Default":"5"}},
                "Resources":{"Launch":{"Type":"AWS::AutoScaling::LaunchConfiguration",
                "Properties":{"LaunchConfigurationName":"%s","ImageId":"ami-amazonlinux2023",
                "InstanceType":"t3.micro","MetadataOptions":{"HttpPutResponseHopLimit":%s}}}}}
                """.formatted(name, hopLimit);
        given().header("Authorization", AUTH).formParam("Action", "CreateStack")
                .formParam("StackName", name).formParam("TemplateBody", template)
                .post("/").then().statusCode(200);

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(name, AUTH);
        assertEquals("CREATE_COMPLETE", state.status(), state.reason());
        given().header("Authorization", AUTH.replace("/cloudformation/", "/autoscaling/"))
                .formParam("Action", "DescribeLaunchConfigurations")
                .formParam("LaunchConfigurationNames.member.1", name).post("/").then().statusCode(200)
                .body("DescribeLaunchConfigurationsResponse.DescribeLaunchConfigurationsResult"
                        + ".LaunchConfigurations.member.MetadataOptions.HttpPutResponseHopLimit", equalTo(stored));
    }
}
