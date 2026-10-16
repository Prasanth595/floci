package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LogsArnTagAuthorizationIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void exactBareGroupArnGrant(String action) {
        String selected = group();
        String other = group(Map.of("team", "green"));
        String caller = user(statement("Allow", action, arn(selected)));
        invoke(caller, action, arn(other)).statusCode(400).body(containsString("AccessDeniedException"));
        assertTags(other, Map.of("team", "green"));
        assertSuccess(invoke(caller, action, arn(selected)), action);
        assertTags(selected, expectedTags(action));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void exactBareGroupArnDenyOverridesWildcard(String action) {
        String selected = group();
        String other = group();
        String caller = user(statement("Allow", action, "*") + "," + statement("Deny", action, arn(selected)));
        ValidatableResponse response = invoke(caller, action, arn(selected));
        assertAll("the exact deny prevents access and preserves tags",
                () -> response.statusCode(400).body(containsString("AccessDeniedException")),
                () -> assertTags(selected, Map.of("team", "blue")));
        assertSuccess(invoke(caller, action, arn(other)), action);
        assertTags(other, expectedTags(action));
    }

    @ParameterizedTest
    @CsvSource({"TagResource,account", "UntagResource,account", "ListTagsForResource,account",
            "TagResource,region", "UntagResource,region", "ListTagsForResource,region",
            "TagResource,partition", "UntagResource,partition", "ListTagsForResource,partition"})
    void foreignArnCannotBorrowLocalGroup(String action, String dimension) {
        verifyForeignArn(action, dimension, false);
    }

    @ParameterizedTest
    @CsvSource({"TagResource,account", "UntagResource,account", "ListTagsForResource,account",
            "TagResource,region", "UntagResource,region", "ListTagsForResource,region",
            "TagResource,partition", "UntagResource,partition", "ListTagsForResource,partition"})
    void rootBypassCannotBorrowLocalGroup(String action, String dimension) {
        verifyForeignArn(action, dimension, true);
    }

    private void verifyForeignArn(String action, String dimension, boolean root) {
        String local = group();
        String caller = root ? ACCOUNT : user(statement("Allow", action, "*"));
        String foreign = switch (dimension) {
            case "account" -> "arn:aws:logs:" + REGION + ":222222222222:log-group:" + local;
            case "region" -> "arn:aws:logs:us-west-2:" + ACCOUNT + ":log-group:" + local;
            case "partition" -> "arn:aws-cn:logs:" + REGION + ":" + ACCOUNT + ":log-group:" + local;
            default -> throw new IllegalArgumentException(dimension);
        };
        ValidatableResponse response = invoke(caller, action, foreign);
        response.statusCode(400).body(containsString("ResourceNotFoundException"));
        if (action.equals("ListTagsForResource")) {
            Map<String, String> returned = response.extract().path("tags");
            assertTrue(returned == null || !"blue".equals(returned.get("team")),
                    "foreign identity must not disclose the local tag entry");
        }
        assertTags(local, Map.of("team", "blue"));
        assertSuccess(invoke(caller, action, arn(local)), action);
        assertTags(local, expectedTags(action));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void incompleteArnPrefixRemainsALiteralLegacyName(String action) {
        String name = groupNamed("arn:archive-" + UUID.randomUUID(), Map.of("team", "blue"));
        String caller = user(statement("Allow", action, arn(name)));
        assertSuccess(invoke(caller, action, name), action);
        assertTags(name, expectedTags(action));
    }

    @ParameterizedTest
    @CsvSource({"TagResource,uppercase,false", "TagResource,uppercase,true",
            "TagResource,space,false", "TagResource,space,true",
            "TagResource,marker,false", "TagResource,marker,true",
            "UntagResource,uppercase,false", "UntagResource,uppercase,true",
            "UntagResource,space,false", "UntagResource,space,true",
            "UntagResource,marker,false", "UntagResource,marker,true",
            "ListTagsForResource,uppercase,false", "ListTagsForResource,uppercase,true",
            "ListTagsForResource,space,false", "ListTagsForResource,space,true",
            "ListTagsForResource,marker,false", "ListTagsForResource,marker,true"})
    void noncanonicalMarkerInputCannotBorrowAnotherGroup(String action, String shape, boolean root) {
        String local = group();
        String input = switch (shape) {
            case "uppercase" -> "ARN:aws:logs:eu-west-1:999999999999:log-group:" + local;
            case "space" -> " arn:aws:logs:eu-west-1:999999999999:log-group:" + local;
            case "marker" -> "x:log-group:" + local;
            default -> throw new IllegalArgumentException(shape);
        };
        String caller = root ? ACCOUNT : user(statement("Allow", action, "*"));
        ValidatableResponse response = invoke(caller, action, input);
        assertAll("noncanonical input cannot access the shortened local name",
                () -> response.statusCode(400).body(containsString("ResourceNotFoundException")),
                () -> assertTags(local, Map.of("team", "blue")));
        String literal = groupNamed(input, Map.of("team", "blue"));
        assertSuccess(invoke(caller, action, input), action);
        assertTags(literal, expectedTags(action));
        assertTags(local, Map.of("team", "blue"));
        assertSuccess(invoke(caller, action, arn(local)), action);
        assertTags(local, expectedTags(action));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TagResource", "UntagResource", "ListTagsForResource"})
    void storedTagDenyUsesTheOwningGroup(String action) {
        String selected = group();
        String other = group(Map.of("team", "green"));
        String caller = user(statement("Allow", action, "*") + ","
                + "{\"Effect\":\"Deny\",\"Action\":\"logs:" + action + "\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:ResourceTag/team\":\"blue\"}}}");
        invoke(caller, action, arn(selected)).statusCode(400).body(containsString("AccessDeniedException"));
        assertTags(selected, Map.of("team", "blue"));
        ValidatableResponse allowed = invoke(caller, action, arn(other)).statusCode(200);
        if (action.equals("ListTagsForResource")) {
            allowed.body("tags", equalTo(Map.of("team", "green")));
            assertTags(other, Map.of("team", "green"));
        } else {
            assertTags(other, expectedTags(action));
        }
    }

    @Test
    void requestedTagValuesAndKeysControlMutation() {
        String selected = group();
        String caller = user("{\"Effect\":\"Allow\",\"Action\":\"logs:TagResource\",\"Resource\":\""
                + arn(selected) + "\",\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"red\"},"
                + "\"ForAllValues:StringEquals\":{\"aws:TagKeys\":[\"team\"]}}}");
        for (Map<String, String> denied : List.of(Map.of("team", "blue"),
                Map.of("team", "red", "protected", "changed"))) {
            call(endpoint.getPort(), caller, "TagResource", Map.of("resourceArn", arn(selected), "tags", denied))
                    .statusCode(400).body(containsString("AccessDeniedException"));
            assertTags(selected, Map.of("team", "blue"));
        }
        invoke(caller, "TagResource", arn(selected)).statusCode(200);
        assertTags(selected, Map.of("team", "red"));
    }

    @Test
    void removalKeysDenyBeforeAnyTagIsRemoved() {
        String selected = group(Map.of("team", "blue", "protected", "retained"));
        String caller = user("{\"Effect\":\"Allow\",\"Action\":\"logs:UntagResource\",\"Resource\":\""
                + arn(selected) + "\",\"Condition\":{\"ForAllValues:StringEquals\":{\"aws:TagKeys\":[\"team\"]}}}");
        call(endpoint.getPort(), caller, "UntagResource", Map.of("resourceArn", arn(selected),
                "tagKeys", List.of("team", "protected")))
                .statusCode(400).body(containsString("AccessDeniedException"));
        assertTags(selected, Map.of("team", "blue", "protected", "retained"));
        invoke(caller, "UntagResource", arn(selected)).statusCode(200);
        assertTags(selected, Map.of("protected", "retained"));
    }

    private void assertSuccess(ValidatableResponse response, String action) {
        response.statusCode(200);
        if (action.equals("ListTagsForResource")) {
            response.body("tags", equalTo(Map.of("team", "blue")));
        }
    }

    private String group() {
        return group(Map.of("team", "blue"));
    }

    private String group(Map<String, String> tags) {
        return groupNamed("logs-arn-tag-" + UUID.randomUUID(), tags);
    }

    private String groupNamed(String name, Map<String, String> tags) {
        int port = endpoint.getPort();
        cleanup.register(() -> call(port, ACCOUNT, "DeleteLogGroup", Map.of("logGroupName", name)).statusCode(200));
        call(port, ACCOUNT, "CreateLogGroup", Map.of("logGroupName", name, "tags", tags))
                .statusCode(200);
        return name;
    }

    private String user(String statements) {
        int port = endpoint.getPort();
        String name = "logs-arn-tag-" + UUID.randomUUID();
        String root = EnforcementFixtures.auth(ACCOUNT, "iam");
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(cleanup, port, root, name);
        given().port(port).header("Authorization", root).formParam("Action", "PutUserPolicy")
                .formParam("UserName", name).formParam("PolicyName", "arn-tags")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}")
                .when().post("/").then().statusCode(200);
        return credentials.accessKeyId();
    }

    private static String statement(String effect, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\",\"Action\":\"logs:" + action
                + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static String arn(String group) {
        return "arn:aws:logs:" + REGION + ":" + ACCOUNT + ":log-group:" + group;
    }

    private ValidatableResponse invoke(String caller, String action, String resource) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resourceArn", resource);
        if (action.equals("TagResource")) {
            body.put("tags", Map.of("team", "red"));
        } else if (action.equals("UntagResource")) {
            body.put("tagKeys", List.of("team"));
        }
        return call(endpoint.getPort(), caller, action, body);
    }

    private Map<String, String> expectedTags(String action) {
        return switch (action) {
            case "TagResource" -> Map.of("team", "red");
            case "UntagResource" -> Map.of();
            case "ListTagsForResource" -> Map.of("team", "blue");
            default -> throw new IllegalArgumentException(action);
        };
    }

    private void assertTags(String group, Map<String, String> expected) {
        Map<String, String> actual = call(endpoint.getPort(), ACCOUNT, "ListTagsLogGroup", Map.of("logGroupName", group))
                .statusCode(200).extract().path("tags");
        assertEquals(expected, actual);
    }

    private static ValidatableResponse call(int port, String caller, String action, Map<String, ?> body) {
        String authorization = "AWS4-HMAC-SHA256 Credential=" + caller + "/20261015/" + REGION
                + "/logs/aws4_request, SignedHeaders=host, Signature=abc";
        return given().port(port).contentType("application/x-amz-json-1.1")
                .header("Authorization", authorization).header("X-Amz-Target", "Logs_20140328." + action)
                .body(new LinkedHashMap<>(body)).when().post("/").then();
    }
}
