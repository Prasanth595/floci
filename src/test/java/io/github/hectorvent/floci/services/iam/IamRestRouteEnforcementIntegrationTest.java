package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * REST requests whose route no rule mapped to an IAM action were let through under enforcement
 * unchecked. These routes are mapped now, and a caller needs the action AWS checks for them.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamRestRouteEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    /** Each user's access key ID, by user name. */
    private final Map<String, String> keys = new HashMap<>();

    @Test
    void apiGatewayManagementRoutesBeyondRestApisAreCheckedByMethod() {
        String user = user("route-apigateway");
        String plan = "{\"name\":\"route-plan-" + suffix() + "\"}";

        policy(user, allow("apigateway:GET", "*"));
        refused(as(user, "apigateway").body(plan).post("/usageplans").then(), "apigateway:POST");
        as(user, "apigateway").get("/usageplans").then().statusCode(200);

        policy(user, allow("apigateway:POST", "*"));
        String id = as(user, "apigateway").body(plan).post("/usageplans").then().statusCode(201).extract().path("id");
        cleanup.register(() -> given().header("Authorization", auth(ADMIN, "apigateway")).delete("/usageplans/" + id));
    }

    @Test
    void getLayerVersionByArnIsCheckedAsGetLayerVersion() {
        String user = user("route-layer");
        String arn = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":layer:route-layer-" + suffix() + ":1";

        policy(user, allow("lambda:ListLayers", "*"));
        refused(as(user, "lambda").queryParam("find", "LayerVersion").queryParam("Arn", arn)
                .get("/2018-10-31/layers").then(), "lambda:GetLayerVersion");
        policy(user, allow("lambda:GetLayerVersion", "*"));
        // Allowed, the request reaches Lambda, which has no such layer.
        as(user, "lambda").queryParam("find", "LayerVersion").queryParam("Arn", arn)
                .get("/2018-10-31/layers").then().statusCode(404);
    }

    @Test
    void aScheduleRequestSignedForAnotherServiceIsStillCheckedAsScheduler() {
        // Checked by the route it reached, not by the credential scope it was signed with: an S3
        // grant does not let the request through as an S3 request.
        String user = user("route-scheduler-scope");
        policy(user, allow("s3:*", "*"));
        refused(as(user, "s3").get("/schedules/route-schedule-" + suffix()).then(), "scheduler:GetSchedule");
    }

    @Test
    void schedulerTaggingIsCheckedOnTheBareTagsRouteToo() {
        // The shared tagging routes also take the ARN in the body of POST /tags.
        String user = user("route-scheduler-tags");
        String group = scheduleGroup("route-group-" + suffix());

        policy(user, allow("scheduler:ListTagsForResource", "*"));
        refused(as(user, "scheduler").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
        policy(user, allow("scheduler:TagResource", group));
        // Allowed on that group, the request reaches Scheduler, which has no such group.
        as(user, "scheduler").body(tagBody(group)).post("/tags").then().statusCode(not(403));
    }

    @Test
    void postTagsIsCheckedAgainstTheArnItsBodyNames() {
        // POST /tags tags the ARN its body names: one named in the query does not move the check.
        String user = user("route-tags-body");
        String allowed = scheduleGroup("route-allowed-" + suffix());
        String other = scheduleGroup("route-other-" + suffix());

        policy(user, allow("scheduler:TagResource", allowed));
        refused(as(user, "scheduler").queryParam("resourceArn", allowed).body(tagBody(other)).post("/tags").then(),
                "scheduler:TagResource");
    }

    @Test
    void sharedTaggingIsCheckedAsTheServiceItsArnNames() {
        // The shared tagging routes hand the request to the service its ARN names, whatever scope it is
        // signed with: a Pipes grant does not let the caller tag a schedule group, and a scope with no
        // tagging rule does not let the request through unchecked.
        String user = user("route-tags-scope");
        String group = scheduleGroup("route-group-" + suffix());
        String cluster = "arn:aws:eks:us-east-1:" + ACCOUNT + ":cluster/route-cluster-" + suffix();

        // Nor is an ARN of a service with no tagging rule checked as the signing scope's action.
        as(user, "pipes").body(tagBody(cluster)).post("/tags").then().body(not(containsString("pipes:TagResource")));
        policy(user, allow("pipes:TagResource", "*"));
        refused(as(user, "pipes").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
        refused(as(user, "sqs").body(tagBody(group)).post("/tags").then(), "scheduler:TagResource");
    }

    private static String scheduleGroup(String name) {
        return "arn:aws:scheduler:us-east-1:" + ACCOUNT + ":schedule-group/" + name;
    }

    private static String tagBody(String arn) {
        return "{\"resourceArn\":\"" + arn + "\",\"tags\":[{\"key\":\"team\",\"value\":\"a\"}]}";
    }

    @Test
    void lambdaTaggingIsCheckedAgainstTheTaggedFunction() {
        String user = user("route-lambda-tags");
        String function = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":function:route-fn-" + suffix();
        String path = "/2017-03-31/tags/" + function;
        String body = "{\"Tags\":{\"team\":\"a\"}}";

        policy(user, allow("lambda:TagResource", function + "-other"));
        refused(as(user, "lambda").body(body).post(path).then(), "lambda:TagResource");
        // A grant on the function itself covers it; the request reaches Lambda, which has no such function.
        policy(user, allow("lambda:TagResource", function));
        as(user, "lambda").body(body).post(path).then().statusCode(not(403));
    }

    @Test
    void lambdaTaggingIsCheckedAgainstTheCallersOwnResourceWhateverAccountTheArnNames() {
        // Lambda tags the caller's own function or MicroVM image of the ARN's name, so a grant on another
        // account's ARN of that name does not cover it, and a grant on the caller's own does.
        String user = user("route-lambda-tags-account");
        String name = "route-tagged-" + suffix();
        String body = "{\"Tags\":{\"team\":\"a\"}}";

        for (String type : new String[] {"function:", "microvm-image:"}) {
            String own = "arn:aws:lambda:us-east-1:" + ACCOUNT + ":" + type + name;
            String foreign = "arn:aws:lambda:us-east-1:111122223333:" + type + name;
            policy(user, allow("lambda:TagResource", foreign));
            refused(as(user, "lambda").body(body).post("/2017-03-31/tags/" + foreign).then(), "lambda:TagResource");
            policy(user, allow("lambda:TagResource", own));
            as(user, "lambda").body(body).post("/2017-03-31/tags/" + foreign).then().statusCode(not(403));
        }
    }

    @Test
    void aMicrovmIsCheckedAgainstTheImageItRuns() {
        // RunMicrovm and the actions on a running MicroVM act on its image, so a Deny on the image applies to
        // them even where the action is allowed on every resource, and a grant on the image alone covers them.
        String user = user("route-microvm-image");
        String image = microvmImage("route-image-" + suffix());
        String run = "{\"imageIdentifier\":\"" + image.substring(image.lastIndexOf(':') + 1) + "\"}";

        policy(user, allow("lambda:RunMicrovm", "*") + "," + deny("lambda:RunMicrovm", image));
        refused(as(user, "lambda").body(run).post("/2025-09-09/microvms").then(), "lambda:RunMicrovm");
        policy(user, allow("lambda:RunMicrovm", image));
        String microvm = as(user, "lambda").body(run).post("/2025-09-09/microvms").then().statusCode(200)
                .extract().path("microvmId");
        cleanup.register(() -> admin().delete("/2025-09-09/microvms/" + microvm));

        policy(user, allow("lambda:GetMicrovm", "*") + "," + deny("lambda:GetMicrovm", image));
        refused(as(user, "lambda").get("/2025-09-09/microvms/" + microvm).then(), "lambda:GetMicrovm");
        policy(user, allow("lambda:GetMicrovm", image));
        as(user, "lambda").get("/2025-09-09/microvms/" + microvm).then().statusCode(200);
    }

    @Test
    void aMicrovmImageTaggedThroughATargetWithoutTheArnPrefixIsCheckedAsTheImage() {
        // Lambda tags the image any target holding :microvm-image: names, read by its fourth field and
        // its last, so a Deny on the image applies however the target is spelled.
        String user = user("route-image-tags");
        String image = microvmImage("route-tagged-image-" + suffix());
        String target = "/2017-03-31/tags/x:y:z:us-east-1:microvm-image:" + image.substring(image.lastIndexOf(':') + 1);
        String body = "{\"Tags\":{\"team\":\"a\"}}";

        policy(user, allow("lambda:TagResource", "*") + "," + deny("lambda:TagResource", image));
        refused(as(user, "lambda").body(body).post(target).then(), "lambda:TagResource");
        policy(user, allow("lambda:TagResource", "*"));
        as(user, "lambda").body(body).post(target).then().statusCode(204);
    }

    /** Creates a MicroVM image as the account's admin, removed afterwards, and returns its ARN. */
    private String microvmImage(String name) {
        admin().body("""
                {"name":"%s","baseImageArn":"arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                 "buildRoleArn":"arn:aws:iam::%s:role/route-build","codeArtifact":{"uri":"s3://bucket/code.zip"}}"""
                .formatted(name, ACCOUNT)).post("/2025-09-09/microvm-images").then().statusCode(201);
        cleanup.register(() -> admin().delete("/2025-09-09/microvm-images/" + name));
        return "arn:aws:lambda:us-east-1:" + ACCOUNT + ":microvm-image:" + name;
    }

    private static RequestSpecification admin() {
        return given().header("Authorization", auth(ADMIN, "lambda")).contentType("application/json");
    }

    private static String deny(String action, String resource) {
        return "{\"Effect\":\"Deny\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static void refused(ValidatableResponse response, String action) {
        response.statusCode(403).body(containsString(action)).body(not(containsString("iam:PassRole")));
    }

    private static String allow(String action, String resource) {
        return "{\"Effect\":\"Allow\",\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private void policy(String user, String statements) {
        EnforcementFixtures.putUserPolicy(baseUri.getPort(), auth(ADMIN, "iam"), user, "p",
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}");
    }

    /** Creates a user, removed afterwards, and returns its access key ID under the user's name. */
    private String user(String name) {
        String userName = name + "-" + suffix();
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(), auth(ADMIN, "iam"), userName)
                .accessKeyId();
        keys.put(userName, accessKeyId);
        return userName;
    }

    private RequestSpecification as(String user, String service) {
        return given().header("Authorization", auth(keys.get(user), service)).contentType("application/json");
    }

    private static String suffix() {
        return Long.toString(System.nanoTime(), 36);
    }
}
