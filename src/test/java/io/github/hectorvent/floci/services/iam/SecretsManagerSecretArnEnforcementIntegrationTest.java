package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code iam.enforcement-enabled=true}, a request that names a secret by its name is
 * authorized against the secret's ARN, random suffix included, as AWS does. The Secrets Manager
 * user guide relies on it: a {@code secret:<name>-??????} resource "enables you to securely grant
 * permissions to a secret that doesn't yet exist".
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SecretsManagerSecretArnEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";
    private static final String SECRET_ARN_PREFIX = "arn:aws:secretsmanager:us-east-1:" + ACCOUNT + ":secret:";

    private record Session(String accessKeyId, String sessionToken) {
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void createSecretIsAuthorizedAgainstTheNewSecretsName() {
        String prefix = "app-" + UUID.randomUUID().toString().substring(0, 8);
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:CreateSecret","Resource":"%s%s/*"}]}"""
                .formatted(SECRET_ARN_PREFIX, prefix));

        secretsCall(session, "CreateSecret", "{\"Name\": \"" + prefix + "/core\", \"SecretString\": \"x\"}")
                .then().statusCode(200);
        secretsCall(session, "CreateSecret", "{\"Name\": \"other-" + prefix + "\", \"SecretString\": \"x\"}")
                .then().statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    @Test
    void aSecretNamedByItsNameIsAuthorizedAgainstItsFullArn() {
        String name = "app-" + UUID.randomUUID().toString().substring(0, 8);
        String arn = createSecret(name);
        Session session = assumeRoleWith("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"secretsmanager:GetSecretValue","Resource":"%s%s-??????"},
                  {"Effect":"Allow","Action":"secretsmanager:DescribeSecret","Resource":"*"},
                  {"Effect":"Deny","Action":"secretsmanager:DescribeSecret","Resource":"%s"}]}"""
                .formatted(SECRET_ARN_PREFIX, name, arn));

        // The documented name-plus-suffix pattern matches a secret read by its name.
        secretsCall(session, "GetSecretValue", "{\"SecretId\": \"" + name + "\"}")
                .then().statusCode(200)
                .body("SecretString", equalTo("x"));
        // A Deny on the stored ARN, suffix included, applies when the request names the secret.
        secretsCall(session, "DescribeSecret", "{\"SecretId\": \"" + name + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("AccessDeniedException"));
    }

    private static String createSecret(String name) {
        Response response = given().header("Authorization", auth(ACCOUNT, "secretsmanager"))
                .header("X-Amz-Target", "secretsmanager.CreateSecret")
                .contentType("application/x-amz-json-1.1")
                .body("{\"Name\": \"" + name + "\", \"SecretString\": \"x\"}")
                .when().post("/");
        response.then().statusCode(200);
        return response.path("ARN");
    }

    /** A session of a new role that its own account may assume, with {@code policy} inline. */
    private static Session assumeRoleWith(String policy) {
        String role = "secret-arn-" + UUID.randomUUID().toString().substring(0, 8);
        given().formParam("Action", "CreateRole").formParam("RoleName", role)
                .formParam("AssumeRolePolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                          "Principal":{"AWS":"arn:aws:iam::%s:root"},"Action":"sts:AssumeRole"}]}""".formatted(ACCOUNT))
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        given().formParam("Action", "PutRolePolicy").formParam("RoleName", role).formParam("PolicyName", "p")
                .formParam("PolicyDocument", policy)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        Response response = given().formParam("Action", "AssumeRole")
                .formParam("RoleArn", "arn:aws:iam::" + ACCOUNT + ":role/" + role)
                .formParam("RoleSessionName", "s")
                .header("Authorization", auth(ACCOUNT, "sts")).when().post("/");
        response.then().statusCode(200);
        return new Session(response.path("AssumeRoleResponse.AssumeRoleResult.Credentials.AccessKeyId"),
                response.path("AssumeRoleResponse.AssumeRoleResult.Credentials.SessionToken"));
    }

    private static Response secretsCall(Session session, String action, String body) {
        return given().header("Authorization", auth(session.accessKeyId(), "secretsmanager"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .header("X-Amz-Target", "secretsmanager." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20261001/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
