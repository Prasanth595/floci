package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures.UserCredentials;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.createUser;
import static io.github.hectorvent.floci.testing.EnforcementFixtures.putUserPolicy;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class DynamoDbPartiQLAuthorizationIntegrationTest {

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentType() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Select", "Insert", "Update", "Delete"})
    void statementUsesItsPartiQLActionOnTheSpecificTable(String operation) {
        String table = table();
        String key = user("dynamodb:PartiQL" + operation, arn(table));
        String statement = switch (operation) {
            case "Select" -> "  select * FROM \"" + table + "\" WHERE pk = 'a'";
            case "Insert" -> "INSERT INTO \"" + table + "\" VALUE {'pk':'b'}";
            case "Update" -> "UPDATE \"" + table + "\" SET data = 'new' WHERE pk = 'a'";
            case "Delete" -> "DELETE FROM \"" + table + "\" WHERE pk = 'a'";
            default -> throw new IllegalArgumentException(operation);
        };
        execute(key, statement).statusCode(200);
        if (!"Select".equals(operation)) {
            execute(key, "SELECT * FROM \"" + table + "\"").statusCode(400)
                    .body(containsString("dynamodb:PartiQLSelect"));
        }
    }

    @Test
    void tableScopedSelectCannotReadAnotherTableOrWrite() {
        String table = table();
        String other = table();
        String key = user("dynamodb:PartiQLSelect", arn(table));
        execute(key, "SELECT * FROM \"" + table + "\" WHERE pk = 'a'")
                .statusCode(200).body("Items[0].pk.S", equalTo("a"));
        execute(key, "SELECT * FROM \"" + other + "\"").statusCode(400)
                .body(containsString("AccessDeniedException"));
        execute(key, "DELETE FROM \"" + table + "\" WHERE pk = 'a'").statusCode(400)
                .body(containsString("dynamodb:PartiQLDelete"));
        execute("test", "SELECT * FROM \"" + table + "\"")
                .statusCode(200).body("Items[0].pk.S", equalTo("a"));
    }

    @Test
    void wireOperationPermissionDoesNotGrantPartiQLAccess() {
        String table = table();
        String key = user("dynamodb:ExecuteStatement", arn(table));
        execute(key, "SELECT * FROM \"" + table + "\"").statusCode(400)
                .body(containsString("dynamodb:PartiQLSelect"));
    }

    private String arn(String table) {
        return "arn:aws:dynamodb:us-east-1:000000000000:table/" + table;
    }

    private String user(String action, String resource) {
        String name = "partiql-" + UUID.randomUUID();
        UserCredentials credentials = createUser(cleanup, endpoint.getPort(), auth("test", "iam"), name);
        putUserPolicy(endpoint.getPort(), auth("test", "iam"), name, "statement", """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s","Resource":"%s"}]}
                """.formatted(action, resource));
        return credentials.accessKeyId();
    }

    private String table() {
        String name = "partiql-" + UUID.randomUUID();
        cleanup.register(() -> json("test", "DeleteTable", Map.of("TableName", name)).statusCode(200));
        json("test", "CreateTable", Map.of("TableName", name,
                "AttributeDefinitions", new Object[]{Map.of("AttributeName", "pk", "AttributeType", "S")},
                "KeySchema", new Object[]{Map.of("AttributeName", "pk", "KeyType", "HASH")},
                "BillingMode", "PAY_PER_REQUEST")).statusCode(200);
        json("test", "PutItem", Map.of("TableName", name, "Item", Map.of("pk", Map.of("S", "a"))))
                .statusCode(200);
        return name;
    }

    private ValidatableResponse execute(String key, String statement) {
        return json(key, "ExecuteStatement", Map.of("Statement", statement));
    }

    private ValidatableResponse json(String key, String operation, Object body) {
        return given().port(endpoint.getPort()).header("Authorization", auth(key, "dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + operation)
                .contentType("application/x-amz-json-1.0").body(body).post("/").then();
    }
}
