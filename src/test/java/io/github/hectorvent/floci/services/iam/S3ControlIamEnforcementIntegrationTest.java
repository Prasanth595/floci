package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each S3 Control operation is authorized as its own IAM action, with the resource type the Service
 * Authorization Reference gives it, never as the S3 object operation its path would read as under
 * the S3 rules: {@code GET /v20180820/accesspoint} is {@code s3:ListAccessPoints} on {@code *}, not
 * {@code s3:GetObject} on an object in a bucket named {@code v20180820}.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class S3ControlIamEnforcementIntegrationTest {

    private static final String ADMIN = "test";
    private static final String ACCOUNT = "000000000000";
    private static final String PUBLIC_ACCESS_BLOCK = "/v20180820/configuration/publicAccessBlock";
    private static final String PUBLIC_ACCESS_BLOCK_XML = "<PublicAccessBlockConfiguration>"
            + "<BlockPublicAcls>false</BlockPublicAcls></PublicAccessBlockConfiguration>";

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    @Test
    void listAccessPointsIsAuthorizedAsListAccessPointsOnAnyResource() {
        String user = "s3c-ap-" + Long.toString(System.nanoTime(), 36);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ADMIN, "iam"), user).accessKeyId();

        policy(user, "{\"Effect\":\"Allow\",\"Action\":\"s3:ListAccessPoints\",\"Resource\":\"*\"}");
        assertEquals(200, call(accessKeyId, "GET", "/v20180820/accesspoint", null),
                "s3:ListAccessPoints on * allows ListAccessPoints");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}");
        assertEquals(403, call(accessKeyId, "GET", "/v20180820/accesspoint", null),
                "s3:GetObject does not allow ListAccessPoints");
    }

    @Test
    void accountPublicAccessBlockIsAuthorizedAsTheAccountActions() {
        String user = "s3c-pab-" + Long.toString(System.nanoTime(), 36);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ADMIN, "iam"), user).accessKeyId();
        cleanup.register(() -> call(ADMIN, "DELETE", PUBLIC_ACCESS_BLOCK, null));

        policy(user, "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetBucketPublicAccessBlock\","
                + "\"s3:PutBucketPublicAccessBlock\",\"s3:GetObject\",\"s3:PutObject\",\"s3:DeleteObject\"],"
                + "\"Resource\":\"*\"}");
        assertEquals(403, call(accessKeyId, "PUT", PUBLIC_ACCESS_BLOCK, PUBLIC_ACCESS_BLOCK_XML),
                "the bucket and object actions do not allow PutPublicAccessBlock");
        assertEquals(403, call(accessKeyId, "GET", PUBLIC_ACCESS_BLOCK, null),
                "the bucket and object actions do not allow GetPublicAccessBlock");
        assertEquals(403, call(accessKeyId, "DELETE", PUBLIC_ACCESS_BLOCK, null),
                "the bucket and object actions do not allow DeletePublicAccessBlock");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetAccountPublicAccessBlock\","
                + "\"s3:PutAccountPublicAccessBlock\"],\"Resource\":\"*\"}");
        assertEquals(200, call(accessKeyId, "PUT", PUBLIC_ACCESS_BLOCK, PUBLIC_ACCESS_BLOCK_XML));
        assertEquals(200, call(accessKeyId, "GET", PUBLIC_ACCESS_BLOCK, null));
        assertEquals(204, call(accessKeyId, "DELETE", PUBLIC_ACCESS_BLOCK, null),
                "DeletePublicAccessBlock is authorized as s3:PutAccountPublicAccessBlock");
    }

    @Test
    void taggingIsAuthorizedAsTheTaggingActionsOnTheBucketItNames() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "s3c-tags-" + suffix;
        String user = "s3c-tags-" + suffix;
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), auth(ADMIN, "s3"), bucket));
        given().header("Authorization", auth(ADMIN, "s3")).when().put("/" + bucket).then().statusCode(200);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ADMIN, "iam"), user).accessKeyId();
        String bucketArn = "arn:aws:s3:::" + bucket;
        String regionalArn = "arn%3Aaws%3As3%3Aus-east-1%3A" + ACCOUNT + "%3Abucket%2F" + bucket;
        String tags = "<Tagging><Tags><Tag><Key>team</Key><Value>a</Value></Tag></Tags></Tagging>";

        policy(user, "{\"Effect\":\"Allow\",\"Action\":[\"s3:GetBucketTagging\",\"s3:PutBucketTagging\","
                + "\"s3:DeleteBucketTagging\",\"s3:GetObject\",\"s3:PutObject\",\"s3:DeleteObject\"],"
                + "\"Resource\":\"*\"}");
        assertEquals(403, call(accessKeyId, "POST", "/v20180820/tags/" + bucketArn, tags),
                "the bucket tagging actions do not allow TagResource");
        assertEquals(403, call(accessKeyId, "GET", "/v20180820/tags/" + bucketArn, null),
                "the bucket tagging actions do not allow ListTagsForResource");
        assertEquals(403, call(accessKeyId, "DELETE", "/v20180820/tags/" + bucketArn + "?tagKeys=team", null),
                "the bucket tagging actions do not allow UntagResource");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":[\"s3:TagResource\",\"s3:ListTagsForResource\","
                + "\"s3:UntagResource\"],\"Resource\":\"" + bucketArn + "\"}");
        assertEquals(204, call(accessKeyId, "POST", "/v20180820/tags/" + bucketArn, tags));
        assertEquals(200, call(accessKeyId, "GET", "/v20180820/tags/" + regionalArn, null),
                "a bucket named in the regional form is authorized against the bucket's own ARN");
        assertEquals(204, call(accessKeyId, "DELETE", "/v20180820/tags/" + bucketArn + "?tagKeys=team", null));
        assertEquals(403, call(accessKeyId, "GET", "/v20180820/tags/arn:aws:s3:::other-" + bucket, null),
                "a policy on one bucket does not cover another");
    }

    @Test
    void taggingIsCheckedAgainstTheTagsTheRequestNames() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String bucket = "s3c-tagcond-" + suffix;
        String user = "s3c-tagcond-" + suffix;
        cleanup.register(() -> EnforcementFixtures.removeBucket(baseUri.getPort(), auth(ADMIN, "s3"), bucket));
        given().header("Authorization", auth(ADMIN, "s3")).when().put("/" + bucket).then().statusCode(200);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ADMIN, "iam"), user).accessKeyId();
        String tags = "/v20180820/tags/arn:aws:s3:::" + bucket;

        policy(user, "{\"Effect\":\"Allow\",\"Action\":\"s3:TagResource\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:RequestTag/team\":\"a\"}}}");
        assertEquals(204, call(accessKeyId, "POST", tags, tagging("team", "a")),
                "a matching aws:RequestTag condition allows TagResource");
        assertEquals(403, call(accessKeyId, "POST", tags, tagging("team", "b")),
                "a non-matching aws:RequestTag condition does not");

        policy(user, "{\"Effect\":\"Allow\",\"Action\":\"s3:UntagResource\",\"Resource\":\"*\","
                + "\"Condition\":{\"ForAllValues:StringEquals\":{\"aws:TagKeys\":[\"team\"]}}}");
        assertEquals(403, call(accessKeyId, "DELETE", tags + "?tagKeys=team&tagKeys=owner", null),
                "a key outside the aws:TagKeys condition is not allowed");
        assertEquals(204, call(accessKeyId, "DELETE", tags + "?tagKeys=team", null),
                "keys inside the aws:TagKeys condition are allowed");
    }

    private static String tagging(String key, String value) {
        return "<Tagging><Tags><Tag><Key>" + key + "</Key><Value>" + value + "</Value></Tag></Tags></Tagging>";
    }

    private static void policy(String user, String statement) {
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                .formParam("PolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[" + statement + "]}")
                .header("Authorization", auth(ADMIN, "iam")).when().post("/").then().statusCode(200);
    }

    private static int call(String accessKeyId, String method, String path, String body) {
        return given().header("Authorization", auth(accessKeyId, "s3"))
                .header("x-amz-account-id", ACCOUNT)
                .contentType("application/xml")
                .body(body == null ? "" : body)
                .when().request(method, path).statusCode();
    }
}
