package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class IamTagKeysEnforcementIntegrationTest {

    @Test
    void ec2CreateTagsRejectsAnUnlistedKeyWithoutNullGuard() {
        String id = given().header("Authorization", auth("test", "ec2"))
                .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.89.0.0/16")
                .post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        String key = scopedUser("ec2:CreateTags");
        given().header("Authorization", auth(key, "ec2"))
                .formParam("Action", "CreateTags").formParam("ResourceId.1", id)
                .formParam("Tag.1.Key", "Team").formParam("Tag.1.Value", "payments")
                .post("/").then().statusCode(200);
        given().header("Authorization", auth(key, "ec2"))
                .formParam("Action", "CreateTags").formParam("ResourceId.1", id)
                .formParam("Tag.1.Key", "Team").formParam("Tag.1.Value", "payments")
                .formParam("Tag.2.Key", "Admin").formParam("Tag.2.Value", "true")
                .post("/").then().statusCode(403).body(containsString("AccessDenied"));
    }

    @Test
    void s3BucketTaggingRejectsAnUnlistedKeyWithoutNullGuard() {
        String bucket = "tagkeys-" + UUID.randomUUID();
        given().header("Authorization", auth("test", "s3")).put("/" + bucket).then().statusCode(200);
        String key = scopedUser("s3:PutBucketTagging");
        String allowed = "<Tag><Key>Team</Key><Value>payments</Value></Tag>";
        given().header("Authorization", auth(key, "s3")).queryParam("tagging", "")
                .body("<Tagging><TagSet>" + allowed + "</TagSet></Tagging>")
                .put("/" + bucket).then().statusCode(204);
        given().header("Authorization", auth(key, "s3")).queryParam("tagging", "")
                .body("<Tagging><TagSet>" + allowed
                        + "<Tag><Key>Admin</Key><Value>true</Value></Tag></TagSet></Tagging>")
                .put("/" + bucket).then().statusCode(403).body(containsString("AccessDenied"));
    }

    private static String scopedUser(String action) {
        String user = "tagkeys-" + UUID.randomUUID();
        given().header("Authorization", auth("test", "iam"))
                .formParam("Action", "CreateUser").formParam("UserName", user)
                .post("/").then().statusCode(200);
        given().header("Authorization", auth("test", "iam"))
                .formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "tags")
                .formParam("PolicyDocument", """
                        {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"%s",
                         "Resource":"*","Condition":{"ForAllValues:StringEquals":{"aws:TagKeys":["Team"]}}}]}
                        """.formatted(action))
                .post("/").then().statusCode(200);
        return given().header("Authorization", auth("test", "iam"))
                .formParam("Action", "CreateAccessKey").formParam("UserName", user)
                .post("/").then().statusCode(200).extract()
                .path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static String auth(String key, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + key + "/20261012/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
