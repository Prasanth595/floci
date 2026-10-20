package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A template holding a null value is refused when the operation is requested, before anything is
 * created. Measured on CloudFormation (eu-west-1, 2026-10-20): ValidateTemplate, CreateStack,
 * UpdateStack and CreateChangeSet answer 400 ValidationError "[path] 'null' values are not allowed
 * in templates", the path naming the first null in document order, with Type where a resource's
 * Properties would be.
 */
@QuarkusTest
class CloudFormationNullValuesIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/cloudformation/aws4_request";

    @ParameterizedTest(name = "{0}")
    @MethodSource("nullValues")
    void aNullValueIsRefusedWhereAwsNamesIt(String where, String template, String path) {
        String message = "[" + path + "] 'null' values are not allowed in templates";
        String name = "cfn-null-" + System.nanoTime();

        assertRefused(request("ValidateTemplate", null, template), message);
        assertRefused(request("CreateStack", name, template), message);
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "DescribeStacks").formParam("StackName", name)
                .when().post("/").then().statusCode(400);
    }

    static Stream<Arguments> nullValues() {
        return Stream.of(
                Arguments.of("a property", queue("{\"QueueName\": null}"), "/Resources/Q/Type/QueueName"),
                Arguments.of("a nested member", queue("{\"RedrivePolicy\": {\"deadLetterTargetArn\": null, \"maxReceiveCount\": 3}}"),
                        "/Resources/Q/Type/RedrivePolicy/deadLetterTargetArn"),
                Arguments.of("a list item's member", queue("{\"Tags\": [{\"Key\": \"k\", \"Value\": null}]}"),
                        "/Resources/Q/Type/Tags/0/Value"),
                Arguments.of("a list entry", "{\"Resources\": {\"T\": {\"Type\": \"AWS::SNS::Topic\", \"Properties\": {\"Subscription\": [null]}}}}",
                        "/Resources/T/Type/Subscription/0"),
                Arguments.of("Properties", "{\"Resources\": {\"Q\": {\"Type\": \"AWS::SQS::Queue\", \"Properties\": null}}}",
                        "/Resources/Q/Properties"),
                Arguments.of("Type", "{\"Resources\": {\"Q\": {\"Type\": null}}}", "/Resources/Q/Type"),
                Arguments.of("an intrinsic's argument", queue("{\"QueueName\": {\"Fn::Join\": [\"-\", [\"a\", null]]}}"),
                        "/Resources/Q/Type/QueueName/Fn::Join/1/1"),
                Arguments.of("an output's value", "{\"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\"}},"
                        + " \"Outputs\": {\"O\": {\"Value\": null}}}", "/Outputs/O/Value"),
                Arguments.of("a parameter's default", "{\"Parameters\": {\"P\": {\"Type\": \"String\", \"Default\": null}},"
                        + " \"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\"}}}", "/Parameters/P/Default"),
                Arguments.of("a resource's metadata", "{\"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\","
                        + " \"Metadata\": {\"m\": null}}}}", "/Resources/H/Metadata/m"),
                Arguments.of("two nulls", queue("{\"QueueName\": null, \"DelaySeconds\": null}"), "/Resources/Q/Type/QueueName"),
                Arguments.of("YAML, empty and ~", "Resources:\n  Q:\n    Type: AWS::SQS::Queue\n    Properties:\n"
                        + "      QueueName:\n      DelaySeconds: ~\n", "/Resources/Q/Type/QueueName"));
    }

    @Test
    void anUpdateAndAChangeSetAreRefusedToo() {
        String name = "cfn-null-update-" + System.nanoTime();
        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "CreateStack").formParam("StackName", name)
                .formParam("TemplateBody", "{\"Resources\": {\"H\": {\"Type\": \"AWS::CloudFormation::WaitConditionHandle\"}}}")
                .when().post("/").then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(name).status());
        String message = "[/Resources/Q/Type/QueueName] 'null' values are not allowed in templates";
        String template = queue("{\"QueueName\": null}");

        assertRefused(request("UpdateStack", name, template), message);
        assertRefused(given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "CreateChangeSet").formParam("StackName", name)
                .formParam("ChangeSetName", "with-null").formParam("TemplateBody", template)
                .when().post("/"), message);

        given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", "DeleteStack").formParam("StackName", name)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(name);
    }

    private static String queue(String properties) {
        return "{\"Resources\": {\"Q\": {\"Type\": \"AWS::SQS::Queue\", \"Properties\": " + properties + "}}}";
    }

    private static Response request(String action, String stackName, String template) {
        RequestSpecification request = given().contentType("application/x-www-form-urlencoded").header("Authorization", AUTH)
                .formParam("Action", action).formParam("TemplateBody", template);
        if (stackName != null) {
            request.formParam("StackName", stackName);
        }
        return request.when().post("/");
    }

    private static void assertRefused(Response response, String message) {
        response.then().statusCode(400);
        assertEquals("ValidationError", response.xmlPath().getString("ErrorResponse.Error.Code"));
        assertEquals(message, response.xmlPath().getString("ErrorResponse.Error.Message"));
    }
}
