package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testutil.AwsRequestSigner;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * GetSessionToken needs no permission: "You cannot use policies to control authentication
 * operations." A deny on it, or no allow at all, still mints credentials, while the other STS
 * actions stay enforced.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class StsGetSessionTokenIamEnforcementIntegrationTest {

    private static final String DENY_SESSION_TOKEN = """
            {"Version":"2012-10-17","Statement":[
              {"Effect":"Allow","Action":"*","Resource":"*"},
              {"Effect":"Deny","Action":"sts:GetSessionToken","Resource":"*"}]}
            """;

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void anExplicitDenyDoesNotStopGetSessionToken() {
        String admin = auth("000000000000", "iam");
        String user = "session-deny-" + UUID.randomUUID();
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), admin, user);
        putPolicy(admin, user, DENY_SESSION_TOKEN);

        sts(credentials, "Action=GetSessionToken&Version=2011-06-15").statusCode(200)
                .body("GetSessionTokenResponse.GetSessionTokenResult.Credentials.AccessKeyId",
                        not(emptyOrNullString()));
    }

    @Test
    void aUserWithNoPolicyCanStillCallGetSessionToken() {
        String admin = auth("000000000000", "iam");
        EnforcementFixtures.UserCredentials credentials = EnforcementFixtures.createUser(
                cleanup, baseUri.getPort(), admin, "session-none-" + UUID.randomUUID());

        sts(credentials, "Action=GetSessionToken&Version=2011-06-15").statusCode(200);
        // Enforcement is on: the same user is refused an action that does need a permission.
        sts(credentials, "Action=AssumeRole&Version=2011-06-15"
                + "&RoleArn=arn%3Aaws%3Aiam%3A%3A000000000000%3Arole%2Fany&RoleSessionName=s")
                .statusCode(403)
                .body("ErrorResponse.Error.Code", equalTo("AccessDenied"));
    }

    private static ValidatableResponse sts(EnforcementFixtures.UserCredentials credentials, String form) {
        AwsRequestSigner signer = AwsRequestSigner.signedAs(credentials.accessKeyId(),
                credentials.secretAccessKey(), "sts");
        return given().filter(signer).contentType("application/x-www-form-urlencoded")
                .body(form).post("/").then();
    }

    private static void putPolicy(String admin, String user, String document) {
        given().header("Authorization", admin).contentType("application/x-www-form-urlencoded")
                .formParam("Action", "PutUserPolicy").formParam("UserName", user)
                .formParam("PolicyName", "session-token")
                .formParam("PolicyDocument", document)
                .post("/").then().statusCode(200);
    }
}
