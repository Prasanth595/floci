package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.createUser;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbReturnValuesAuthorizationIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentType() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete"})
    void disallowedReturnValuesAreRejectedBeforeChangingTheItem(String operation) {
        String table = table();
        String caller = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"StringEqualsIfExists\":{\"dynamodb:ReturnValues\":\"NONE\"}}"));
        request(caller, operation + "Item", withReturnValues(operation, table, "\"ALL_OLD\""))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertOriginal(table);
        request(caller, operation + "Item", withReturnValues(operation, table, "\"NONE\""))
                .statusCode(200);
        assertChanged(operation, table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete"})
    void omittedAndNullReturnValuesUseTheSameNoneDefaultAsExecution(String operation) {
        String caller = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"NONE\"}}"));
        for (String value : new String[]{null, "null", "\"NONE\""}) {
            String table = table();
            String body = value == null ? itemBody(operation, table) : withReturnValues(operation, table, value);
            request(caller, operation + "Item", body).statusCode(200);
            assertChanged(operation, table);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete"})
    void explicitOldImageIsAvailableOnlyWhenItsConditionMatches(String operation) {
        String table = table();
        String caller = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"ALL_OLD\"},"
                        + "\"Null\":{\"dynamodb:ReturnValues\":\"false\"}}"));
        request(caller, operation + "Item", itemBody(operation, table))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertOriginal(table);
        request(caller, operation + "Item", withReturnValues(operation, table, "\"ALL_OLD\""))
                .statusCode(200).body("Attributes.value.S", equalTo("original"));
        assertChanged(operation, table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALL_NEW", "UPDATED_NEW", "UPDATED_OLD"})
    void updateSpecificReturnModesReachTheHandlerUnchanged(String mode) {
        String table = table();
        String caller = user(policy("dynamodb:UpdateItem",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"" + mode + "\"}}"));
        request(caller, "UpdateItem", withReturnValues("Update", table, "\"ALL_OLD\""))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertOriginal(table);
        request(caller, "UpdateItem", withReturnValues("Update", table, "\"" + mode + "\""))
                .statusCode(200).body("Attributes.value.S", equalTo("UPDATED_OLD".equals(mode) ? "original" : "changed"));
        assertChanged("Update", table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete"})
    void strictNonePolicyCannotExposeAConditionalFailureImage(String operation) {
        String table = table();
        String caller = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"NONE\"}}"));
        String body = withReturnValues(operation, table, "\"NONE\"");
        body = body.substring(0, body.length() - 1)
                + ",\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\","
                + "\"ConditionExpression\":\"attribute_not_exists(PK)\"}";
        request(caller, operation + "Item", body).statusCode(400)
                .body(containsString("AccessDeniedException"));
        assertOriginal(table);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete"})
    void broadPolicyControlCanReceiveAConditionalFailureImage(String operation) {
        String table = table();
        String caller = user(policy("dynamodb:" + operation + "Item", ""));
        String body = withReturnValues(operation, table, "\"NONE\"");
        body = body.substring(0, body.length() - 1)
                + ",\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\","
                + "\"ConditionExpression\":\"attribute_not_exists(PK)\"}";
        request(caller, operation + "Item", body).statusCode(400)
                .body(containsString("ConditionalCheckFailedException"))
                .body("Item.value.S", equalTo("original"));
        assertOriginal(table);
    }

    @ParameterizedTest
    @CsvSource({"UPDATED_NEW,true", "UPDATED_NEW,false", "ALL_OLD,true", "ALL_OLD,false"})
    void differingModesCannotBeAuthorizedByOnlyOneGrant(String grant, boolean succeeds) {
        String table = table();
        String caller = user(policy("dynamodb:UpdateItem",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"" + grant + "\"}}"));
        request(caller, "UpdateItem", conditionalBody("Update", table, "UPDATED_NEW", succeeds))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertOriginal(table);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aGrantCoveringBothModesAllowsTheActualResult(boolean succeeds) {
        String table = table();
        String caller = user(policy("dynamodb:UpdateItem",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":[\"UPDATED_NEW\",\"ALL_OLD\"]}}"));
        ValidatableResponse response = request(caller, "UpdateItem",
                conditionalBody("Update", table, "UPDATED_NEW", succeeds));
        if (succeeds) {
            response.statusCode(200).body("Attributes.value.S", equalTo("changed"));
            assertChanged("Update", table);
        } else {
            response.statusCode(400).body(containsString("ConditionalCheckFailedException"))
                    .body("Item.value.S", equalTo("original"));
            assertOriginal(table);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Put", "Update", "Delete"})
    void noneSuccessAndOldFailureNeedOnlyTheOldGrant(String operation) {
        String caller = user(policy("dynamodb:" + operation + "Item",
                ",\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"ALL_OLD\"}}"));
        for (boolean succeeds : new boolean[]{true, false}) {
            String table = table();
            ValidatableResponse response = request(caller, operation + "Item",
                    conditionalBody(operation, table, "NONE", succeeds));
            if (succeeds) {
                response.statusCode(200).body(equalTo("{}"));
                assertChanged(operation, table);
            } else {
                response.statusCode(400).body(containsString("ConditionalCheckFailedException"))
                        .body("Item.value.S", equalTo("original"));
                assertOriginal(table);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anExplicitDenyOfTheFailureModeOverridesTheBroadGrant(boolean succeeds) {
        String table = table();
        String caller = user("{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Action\":\"dynamodb:UpdateItem\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Action\":\"dynamodb:UpdateItem\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"dynamodb:ReturnValues\":\"ALL_OLD\"}}}]}");
        request(caller, "UpdateItem", conditionalBody("Update", table, "UPDATED_NEW", succeeds))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertOriginal(table);
    }

    private static String conditionalBody(String operation, String table, String successfulMode, boolean succeeds) {
        String body = withReturnValues(operation, table, "\"" + successfulMode + "\"");
        return body.substring(0, body.length() - 1)
                + ",\"ReturnValuesOnConditionCheckFailure\":\"ALL_OLD\",\"ConditionExpression\":\""
                + (succeeds ? "attribute_exists(PK)" : "attribute_not_exists(PK)") + "\"}";
    }

    private void assertOriginal(String table) {
        request("test", "GetItem", keyBody(table)).statusCode(200).body("Item.value.S", equalTo("original"));
    }

    private void assertChanged(String operation, String table) {
        ValidatableResponse response = request("test", "GetItem", keyBody(table)).statusCode(200);
        if ("Delete".equals(operation)) {
            response.body(equalTo("{}"));
        } else {
            response.body("Item.value.S", equalTo("changed"));
        }
    }

    private static String withReturnValues(String operation, String table, String value) {
        String body = itemBody(operation, table);
        return body.substring(0, body.length() - 1) + ",\"ReturnValues\":" + value + "}";
    }

    private static String itemBody(String operation, String table) {
        if ("Put".equals(operation)) {
            return "{\"TableName\":\"" + table + "\",\"Item\":{\"PK\":{\"S\":\"item\"},"
                    + "\"value\":{\"S\":\"changed\"}}}";
        }
        String base = keyBody(table);
        if ("Update".equals(operation)) {
            return base.substring(0, base.length() - 1) + ",\"UpdateExpression\":\"SET #v = :v\","
                    + "\"ExpressionAttributeNames\":{\"#v\":\"value\"},"
                    + "\"ExpressionAttributeValues\":{\":v\":{\"S\":\"changed\"}}}";
        }
        return base;
    }

    private static String keyBody(String table) {
        return "{\"TableName\":\"" + table + "\",\"Key\":{\"PK\":{\"S\":\"item\"}}}";
    }

    private String table() {
        String table = "return-values-" + UUID.randomUUID();
        int port = endpoint.getPort();
        cleanup.register(() -> given().port(port).header("Authorization", auth("test", "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810.DeleteTable")
                .contentType("application/x-amz-json-1.0").body("{\"TableName\":\"" + table + "\"}")
                .post("/").then().statusCode(200));
        request("test", "CreateTable", """
                {"TableName":"%s","KeySchema":[{"AttributeName":"%s","KeyType":"HASH"}],
                 "AttributeDefinitions":[{"AttributeName":"%s","AttributeType":"S"}],
                 "BillingMode":"PAY_PER_REQUEST"}
                """.formatted(table, "PK", "PK")).statusCode(200);
        request("test", "PutItem", itemBody("Put", table).replace("changed", "original")).statusCode(200);
        return table;
    }

    private String user(String policy) {
        String name = "return-values-" + UUID.randomUUID();
        String key = createUser(cleanup, endpoint.getPort(), auth("test", "iam"), name).accessKeyId();
        given().header("Authorization", auth("test", "iam")).formParam("Action", "PutUserPolicy")
                .formParam("UserName", name).formParam("PolicyName", "return-values")
                .formParam("PolicyDocument", policy).post("/").then().statusCode(200);
        return key;
    }

    private static String policy(String action, String condition) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\""
                + action + "\",\"Resource\":\"*\"" + condition + "}]}";
    }

    private ValidatableResponse request(String key, String action, String body) {
        return given().header("Authorization", auth(key, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }
}
