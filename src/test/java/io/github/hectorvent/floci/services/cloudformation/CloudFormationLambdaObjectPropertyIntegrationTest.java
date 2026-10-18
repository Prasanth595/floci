package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * An object property of {@code AWS::Lambda::Function} that is not an object. Measured on AWS
 * (eu-west-1, 2026-10-18): a VpcConfig, DeadLetterConfig or LoggingConfig that is a string, a number,
 * a boolean or a list fails the stack before the function is touched, on create and on update,
 * with "Property [VpcConfig] expected type: JSONObject, found: String" ("found: JSONArray" for a
 * list). It is never read as an absent property, which would reset the function's setting.
 */
@QuarkusTest
class CloudFormationLambdaObjectPropertyIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String LAMBDA = "/2015-03-31/functions/";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void aPropertyThatIsNotAnObjectFailsTheCreate() {
        String vpc = stackName("vpc");
        submit("CreateStack", vpc, template(",\"VpcConfig\": 5"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(vpc, CFN_AUTH).status());
        assertEventReason(vpc, "Property [VpcConfig] expected type: JSONObject, found: String");

        String deadLetter = stackName("dlq");
        submit("CreateStack", deadLetter, template(",\"DeadLetterConfig\": [\"x\"]"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(deadLetter, CFN_AUTH).status());
        assertEventReason(deadLetter, "Property [DeadLetterConfig] expected type: JSONObject, found: JSONArray");
    }

    @Test
    void aPropertyThatIsNotAnObjectFailsTheUpdateAndKeepsTheSetting() {
        String stack = stackName("update");
        submit("CreateStack", stack, template(",\"TracingConfig\": {\"Mode\": \"Active\"}"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        String function = functionName(stack);

        submit("UpdateStack", stack, template(",\"TracingConfig\": \"Active\""));
        assertNotEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stack, CFN_AUTH).status());
        assertEventReason(stack, "Property [TracingConfig] expected type: JSONObject, found: String");
        given()
        .when()
            .get(LAMBDA + function + "/configuration")
        .then()
            .statusCode(200)
            .body("TracingConfig.Mode", equalTo("Active"));
    }

    /**
     * Measured on AWS (eu-west-1, 2026-10-18): {@code Ref: AWS::NoValue}, directly or chosen by
     * {@code Fn::If}, leaves the property out, and the stack is created.
     */
    @Test
    void aPropertyLeftOutWithNoValueIsNotRefused() {
        String direct = stackName("novalue");
        submit("CreateStack", direct, template(
                ",\"VpcConfig\": {\"Ref\": \"AWS::NoValue\"}, \"TracingConfig\": {\"Ref\": \"AWS::NoValue\"}"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(direct, CFN_AUTH).status());

        String conditional = stackName("novalue-if");
        submit("CreateStack", conditional, """
                {
                  "Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        "DeadLetterConfig": {"Fn::If": ["Never",
                            {"TargetArn": "arn:aws:sqs:us-east-1:000000000000:none"}, {"Ref": "AWS::NoValue"}]}
                      }
                    }
                  }
                }
                """);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(conditional, CFN_AUTH).status());
    }

    private String stackName(String purpose) {
        String name = "cfn-lambda-object-" + purpose + "-" + Long.toString(System.nanoTime(), 36);
        cleanup.register(() -> {
            submit("DeleteStack", name, null);
            CfnStackWaits.awaitStackDeleted(name, CFN_AUTH);
        });
        return name;
    }

    private static String template(String property) {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
                        %s
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(property);
    }

    private static void submit(String action, String stackName, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static void assertEventReason(String stackName, String reason) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(reason));
    }

    private static String functionName(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.name() == 'OutputValue' }");
    }
}
