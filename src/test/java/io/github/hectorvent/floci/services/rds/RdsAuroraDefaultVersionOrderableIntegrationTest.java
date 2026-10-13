package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An Aurora MySQL cluster created without EngineVersion must get a version that
 * DescribeOrderableDBInstanceOptions lists, or a caller sizing instances for that cluster
 * finds no orderable class at all.
 */
@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsAuroraDefaultVersionOrderableIntegrationTest {

    private static final String ID = "aurora-default-version-cluster";

    private static io.restassured.specification.RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20260615/us-east-1/rds/aws4_request, "
                        + "SignedHeaders=content-type;host, Signature=test")
                .contentType(URLENC)
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }

    @AfterEach
    void cleanUp() {
        rds("DeleteDBCluster").formParam("DBClusterIdentifier", ID)
                .formParam("SkipFinalSnapshot", "true").when().post("/");
    }

    @Test
    void defaultAuroraMysqlVersionIsOrderable() {
        rds("CreateDBCluster")
                .formParam("DBClusterIdentifier", ID)
                .formParam("Engine", "aurora-mysql")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "secret99password")
                .when().post("/").then().statusCode(200);

        String described = rds("DescribeDBClusters").formParam("DBClusterIdentifier", ID)
                .when().post("/").then().statusCode(200).extract().asString();
        Matcher version = Pattern.compile("<EngineVersion>([^<]+)</EngineVersion>").matcher(described);
        assertTrue(version.find(), described);

        rds("DescribeOrderableDBInstanceOptions")
                .formParam("Engine", "aurora-mysql")
                .formParam("EngineVersion", version.group(1))
                .when().post("/").then().statusCode(200)
                .body(containsString("<OrderableDBInstanceOption>"));
    }
}
