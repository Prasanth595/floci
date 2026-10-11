package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * The two filters {@code DescribeStackResources} accepts.
 *
 * <p>Ignoring them is quiet rather than loud: a caller that filters and reads the first member
 * gets whichever resource the stack happens to list first, and nothing in the response says the
 * filter was dropped. Serverless Framework resolves an AppSync API id exactly that way, which is
 * how this surfaced (issue #5451).
 */
@QuarkusTest
class CloudFormationDescribeStackResourcesFilterIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";

    private String createStack(String suffix) {
        String stackName = "cfn-dsr-" + suffix;
        String template = """
                {
                  "Resources": {
                    "MyQueue": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "cfn-dsr-q-%s"}},
                    "MyTopic": {"Type": "AWS::SNS::Topic", "Properties": {"TopicName": "cfn-dsr-t-%s"}}
                  }
                }
                """.formatted(suffix, suffix);
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        return stackName;
    }

    private RequestSpecification describe() {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResources");
    }

    @Test
    void logicalResourceIdNarrowsToThatOneResource() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);

        describe()
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", "MyTopic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyTopic</LogicalResourceId>"))
            .body(not(containsString("<LogicalResourceId>MyQueue</LogicalResourceId>")))
            // AWS stamps the stack's own name on every member.
            .body(containsString("<StackName>" + stackName + "</StackName>"));
    }

    @Test
    void aLogicalResourceIdTheStackDoesNotHaveIsAValidationError() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);

        // Not an empty list: a caller must not read the miss as "the stack has no resources".
        describe()
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", "NoSuchResource")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("does not exist for stack " + stackName));
    }

    @Test
    void physicalResourceIdFindsTheStackWithoutAStackName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);
        String topicName = "cfn-dsr-t-" + suffix;

        // AWS takes either StackName or PhysicalResourceId; given only the latter it resolves the
        // stack that owns the resource.
        describe()
            .formParam("PhysicalResourceId", "arn:aws:sns:us-east-1:000000000000:" + topicName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyTopic</LogicalResourceId>"))
            .body(not(containsString("<LogicalResourceId>MyQueue</LogicalResourceId>")))
            .body(containsString("<StackName>" + stackName + "</StackName>"));
    }

    @Test
    void neitherStackNameNorPhysicalResourceIdIsAValidationError() {
        describe()
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("Either StackName or PhysicalResourceId must be specified."));
    }

    @Test
    void withoutAFilterEveryResourceStillComesBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = createStack(suffix);

        describe()
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<LogicalResourceId>MyQueue</LogicalResourceId>"))
            .body(containsString("<LogicalResourceId>MyTopic</LogicalResourceId>"));
    }
}
