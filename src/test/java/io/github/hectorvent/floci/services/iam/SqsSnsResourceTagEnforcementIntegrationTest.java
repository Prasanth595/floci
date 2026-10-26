package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;

/**
 * Proves that SQS and SNS requests see the target queue's or topic's {@code aws:ResourceTag}
 * keys, which an ABAC policy conditions on.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SqsSnsResourceTagEnforcementIntegrationTest {

    private static final String CALLER_ACCOUNT_ID = "111122223333";
    private static final String ROLE_ACCOUNT_ID = "222233334444";
    private static final String REGION = "us-east-1";

    @Test
    void aTaggedQueueAllowsTheDataActionsAndAnUntaggedQueueDeniesThem() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedQueue = createQueue("tags-tagged-" + suffix);
        String untaggedQueue = createQueue("tags-untagged-" + suffix);
        tagQueue(taggedQueue, "team", "blue");
        String role = "SqsResourceTagRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":["sqs:SendMessage","sqs:ReceiveMessage","sqs:DeleteMessage"],
                   "Resource":"*","Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                """);
        String accessKey = assumeRole(role);

        sendMessage(accessKey, taggedQueue, "allowed", 200);
        sendMessage(accessKey, untaggedQueue, "denied", 403);
        receiveMessage(accessKey, taggedQueue, 200);
        receiveMessage(accessKey, untaggedQueue, 403);
    }

    @Test
    void aQueueTagIsReadFromTheQueuesAccountNotTheCallers() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedQueue = createQueue("tags-cross-tagged-" + suffix);
        String untaggedQueue = createQueue("tags-cross-untagged-" + suffix);
        tagQueue(taggedQueue, "team", "blue");
        String accessKey = createUserWithSqsTagPolicy("CrossAccountSqsUser" + suffix);

        sendMessage(accessKey, taggedQueue, "allowed", 200);
        sendMessage(accessKey, untaggedQueue, "denied", 403);
    }

    @Test
    void aTaggedTopicAllowsPublishAndAnUntaggedTopicDeniesIt() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String taggedTopic = createTopic("tags-tagged-" + suffix);
        String untaggedTopic = createTopic("tags-untagged-" + suffix);
        tagTopic(taggedTopic, "team", "blue");
        String role = "SnsResourceTagRole" + suffix;
        createRole(role);
        putRolePolicy(role, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"sns:Publish","Resource":"*",
                   "Condition":{"StringLike":{"aws:ResourceTag/team":"bl*"}}}]}
                """);
        String accessKey = assumeRole(role);

        publish(accessKey, taggedTopic, 200);
        publish(accessKey, untaggedTopic, 403);
    }

    private static String createQueue(String queueName) {
        return given()
                .formParam("Action", "CreateQueue")
                .formParam("QueueName", queueName)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("CreateQueueResponse.CreateQueueResult.QueueUrl", startsWith("http"))
                .extract()
                .path("CreateQueueResponse.CreateQueueResult.QueueUrl");
    }

    private static void tagQueue(String queueUrl, String key, String value) {
        given()
                .formParam("Action", "TagQueue")
                .formParam("QueueUrl", queueUrl)
                .formParam("Tag.1.Key", key)
                .formParam("Tag.1.Value", value)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void sendMessage(String accessKey, String queueUrl, String body, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "SendMessage")
                .formParam("QueueUrl", queueUrl)
                .formParam("MessageBody", body)
                .header("Authorization", auth(accessKey, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("AccessDenied"));
        }
    }

    private static void receiveMessage(String accessKey, String queueUrl, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "ReceiveMessage")
                .formParam("QueueUrl", queueUrl)
                .header("Authorization", auth(accessKey, "sqs"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("AccessDenied"));
        }
    }

    private static String createTopic(String topicName) {
        return given()
                .formParam("Action", "CreateTopic")
                .formParam("Name", topicName)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("CreateTopicResponse.CreateTopicResult.TopicArn", startsWith("arn:"))
                .extract()
                .path("CreateTopicResponse.CreateTopicResult.TopicArn");
    }

    private static void tagTopic(String topicArn, String key, String value) {
        given()
                .formParam("Action", "TagResource")
                .formParam("ResourceArn", topicArn)
                .formParam("Tags.member.1.Key", key)
                .formParam("Tags.member.1.Value", value)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void publish(String accessKey, String topicArn, int status) {
        ValidatableResponse response = given()
                .formParam("Action", "Publish")
                .formParam("TopicArn", topicArn)
                .formParam("Message", "hello")
                .header("Authorization", auth(accessKey, "sns"))
        .when()
                .post("/")
        .then()
                .statusCode(status);
        if (status != 200) {
            response.body(containsString("AccessDenied"));
        }
    }

    private static void createRole(String roleName) {
        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {"Effect": "Allow", "Principal": {"AWS": "*"}, "Action": "sts:AssumeRole"}
                      ]
                    }
                    """)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static void putRolePolicy(String roleName, String policyDocument) {
        given()
                .formParam("Action", "PutRolePolicy")
                .formParam("RoleName", roleName)
                .formParam("PolicyName", "ResourceTagPolicy")
                .formParam("PolicyDocument", policyDocument)
                .header("Authorization", auth(ROLE_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static String createUserWithSqsTagPolicy(String userName) {
        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", userName)
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
        String accessKey = given()
                .formParam("Action", "CreateAccessKey")
                .formParam("UserName", userName)
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract()
                .path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
        given()
                .formParam("Action", "PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "ResourceTagPolicy")
                .formParam("PolicyDocument", """
                    {"Version":"2012-10-17","Statement":[
                      {"Effect":"Allow","Action":"sqs:SendMessage","Resource":"*",
                       "Condition":{"StringEquals":{"aws:ResourceTag/team":"blue"}}}]}
                    """)
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "iam"))
        .when()
                .post("/")
        .then()
                .statusCode(200);
        return accessKey;
    }

    private static String assumeRole(String roleName) {
        return given()
                .formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ROLE_ACCOUNT_ID + ":role/" + roleName)
                .formParam("RoleSessionName", "resource-tag-test")
                .header("Authorization", auth(CALLER_ACCOUNT_ID, "sts"))
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId", startsWith("ASIA"))
                .extract()
                .path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId");
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260904/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
