package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.OperationEvent;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An object property of AWS::Lambda::Function that is not an object, through the CloudFormation SDK
 * client. Measured on AWS (eu-west-1, 2026-10-18): the stack fails before the function is touched,
 * "Property [VpcConfig] expected type: JSONObject, found: String" ("found: JSONArray" for a list).
 * AWS reports it from its pre-deployment validation, read through DescribeEvents; Floci, which
 * does not model that validation, reports the same message on the resource's failed event.
 */
@DisplayName("CloudFormation Lambda object properties")
class CloudFormationLambdaObjectPropertyTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/cfn-lambda-role";
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(30);
    private static final String STRING_STACK = TestFixtures.uniqueName("compat-cfn-object-string");
    private static final String LIST_STACK = TestFixtures.uniqueName("compat-cfn-object-list");

    private static CloudFormationClient cfn;

    @BeforeAll
    static void setup() {
        cfn = TestFixtures.cloudFormationClient();
    }

    @AfterAll
    static void cleanup() {
        if (cfn != null) {
            // DeleteStack answers for a stack that is gone too, so a failure here is a real one.
            cfn.deleteStack(request -> request.stackName(STRING_STACK));
            cfn.deleteStack(request -> request.stackName(LIST_STACK));
            cfn.close();
        }
    }

    @Test
    @DisplayName("a VpcConfig that is a string or a DeadLetterConfig that is a list fails the stack")
    void anObjectPropertyThatIsNotAnObjectFailsTheStack() throws InterruptedException {
        cfn.createStack(request -> request.stackName(STRING_STACK).templateBody(template("\"VpcConfig\": \"x\"")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, STRING_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(STRING_STACK)).anyMatch(reason ->
                reason.contains("Property [VpcConfig] expected type: JSONObject, found: String"));

        cfn.createStack(request -> request.stackName(LIST_STACK)
                .templateBody(template("\"DeadLetterConfig\": [\"x\"]")));
        assertThat(TestFixtures.awaitStackTerminal(cfn, LIST_STACK, DEPLOY_TIMEOUT)).isEqualTo("ROLLBACK_COMPLETE");
        assertThat(reasons(LIST_STACK)).anyMatch(reason ->
                reason.contains("Property [DeadLetterConfig] expected type: JSONObject, found: JSONArray"));
    }

    private static List<String> reasons(String stackName) {
        if (TestFixtures.isRealAws()) {
            return cfn.describeEvents(request -> request.stackName(stackName)).operationEvents().stream()
                    .map(OperationEvent::validationStatusReason)
                    .filter(reason -> reason != null)
                    .toList();
        }
        return cfn.describeStackEvents(request -> request.stackName(stackName)).stackEvents().stream()
                .map(StackEvent::resourceStatusReason)
                .filter(reason -> reason != null)
                .toList();
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
                        "Role": "%s",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"},
                        %s
                      }
                    }
                  }
                }
                """.formatted(ROLE, property);
    }
}
