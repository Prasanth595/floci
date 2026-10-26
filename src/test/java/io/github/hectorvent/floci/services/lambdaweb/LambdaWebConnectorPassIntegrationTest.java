package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.EnforcementFixtures.Session;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Under IAM enforcement a revision needs lambda:PassNetworkConnector on the connector version it names. */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class LambdaWebConnectorPassIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final String CONNECTOR =
            "arn:aws:lambda:us-east-1:000000000000:network-connector:nc-0123456789abcdef0";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Inject
    IamService iamService;

    @Test
    void aRevisionNeedsPassNetworkConnectorOnTheVersionItNames() {
        int port = baseUri.getPort();
        String name = "connector-pass-" + Long.toString(System.nanoTime(), 36);
        String denyArn = "arn:aws:iam::" + ACCOUNT + ":policy/" + name;
        cleanup.register(() -> EnforcementFixtures.removePolicy(port, auth(ACCOUNT, "iam"), denyArn));
        LambdaWebRoles.trustLambda(iamService, ROLE);
        String roleArn = EnforcementFixtures.createRole(cleanup, port, ACCOUNT, "/", name, policy(""));
        Session session = EnforcementFixtures.assumeRole(port, ACCOUNT, roleArn);
        String denied = "User: arn:aws:sts::" + ACCOUNT + ":assumed-role/" + name + "/s is not authorized to perform:"
                + " lambda:PassNetworkConnector on resource: " + CONNECTOR + ":1 ";

        revision(session, CONNECTOR + ":1").statusCode(403)
                .body("Message", equalTo(denied
                        + "because no identity-based policy allows the lambda:PassNetworkConnector action"));
        revision(session, CONNECTOR + ":0").statusCode(400)
                .body("message", equalTo("The egress network connector ARN: '" + CONNECTOR + ":0' is not valid."
                        + " Invalid network connector version format - must be numeric: 0"));

        iamService.putRolePolicy(name, "p", policy(","
                + "{\"Effect\":\"Allow\",\"Action\":\"lambda:PassNetworkConnector\",\"Resource\":\"" + CONNECTOR + ":1\"}"));
        revision(session, CONNECTOR + ":1").statusCode(404)
                .body("message", equalTo("The function arn:aws:lambda:us-east-1:" + ACCOUNT
                        + ":web-function/missing does not exist."));

        iamService.createPolicy(name, "/", null, """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Deny","Action":"lambda:PassNetworkConnector","Resource":"*"}]}""", null);
        iamService.attachRolePolicy(name, denyArn);
        revision(session, CONNECTOR + ":1").statusCode(403)
                .body("Message", equalTo(denied + "with an explicit deny in an identity-based policy: " + denyArn));
    }

    private static ValidatableResponse revision(Session session, String connectorArn) {
        return given().header("Authorization", auth(session.accessKeyId(), "lambda"))
                .header("X-Amz-Security-Token", session.sessionToken())
                .contentType("application/json")
                .body("""
                        {"buildConfig": {"codeConfig": {"s3Object": {"bucket": "web-code", "key": "app.zip"}},
                                         "runtimeConfig": {"runtime": "nodejs24.x"}},
                         "serviceConfig": {"executionRoleArn": "%s", "egressNetworkConnectorArn": "%s"}}
                        """.formatted(ROLE, connectorArn))
                .when().post("/2025-03-07/web-functions/missing/revisions")
                .then();
    }

    private static String policy(String more) {
        return """
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"lambda:CreateWebFunctionRevision","Resource":"*"},
                  {"Effect":"Allow","Action":"iam:PassRole","Resource":"*"}%s]}""".formatted(more);
    }
}
