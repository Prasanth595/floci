package io.github.hectorvent.floci.services.rds;

import io.github.hectorvent.floci.testing.RdsMockProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.URLENC;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(RdsMockProfile.class)
class RdsClusterRestoreIamIntegrationTest {
    private static RequestSpecification rds(String action) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=test/20261011/us-east-1/rds/aws4_request")
                .contentType(URLENC).formParam("Action", action).formParam("Version", "2014-10-31");
    }

    @ParameterizedTest
    @CsvSource({"snapshot, , false", "snapshot, false, false", "snapshot, true, true",
            "point, , false", "point, false, false", "point, true, true"})
    void restoredIamFlagDefaultsToFalse(String path, String flag, boolean expected) {
        String source = "iam-source-" + UUID.randomUUID();
        String snapshot = source + "-snap";
        String target = source + "-target";
        try {
            rds("CreateDBCluster").formParam("DBClusterIdentifier", source)
                    .formParam("Engine", "aurora-postgresql").formParam("MasterUsername", "admin")
                    .formParam("MasterUserPassword", "password123")
                    .formParam("EnableIAMDatabaseAuthentication", "true")
                    .post("/").then().statusCode(200);
            rds("CreateDBClusterSnapshot").formParam("DBClusterIdentifier", source)
                    .formParam("DBClusterSnapshotIdentifier", snapshot).post("/").then().statusCode(200);
            RequestSpecification request;
            if ("snapshot".equals(path)) {
                request = rds("RestoreDBClusterFromSnapshot").formParam("SnapshotIdentifier", snapshot)
                        .formParam("Engine", "aurora-postgresql");
            } else {
                request = rds("RestoreDBClusterToPointInTime").formParam("SourceDBClusterIdentifier", source)
                        .formParam("UseLatestRestorableTime", "true");
            }
            request.formParam("DBClusterIdentifier", target);
            if (flag != null) {
                request.formParam("EnableIAMDatabaseAuthentication", flag);
            }
            request.post("/").then().statusCode(200)
                    .body(containsString("<IAMDatabaseAuthenticationEnabled>" + expected
                            + "</IAMDatabaseAuthenticationEnabled>"));
            rds("DescribeDBClusters").formParam("DBClusterIdentifier", target).post("/").then().statusCode(200)
                    .body(containsString("<IAMDatabaseAuthenticationEnabled>" + expected
                            + "</IAMDatabaseAuthenticationEnabled>"));
            rds("DescribeDBClusters").formParam("DBClusterIdentifier", source).post("/").then().statusCode(200)
                    .body(containsString("<IAMDatabaseAuthenticationEnabled>true</IAMDatabaseAuthenticationEnabled>"));
        } finally {
            for (String id : new String[]{target, source}) {
                rds("DeleteDBCluster").formParam("DBClusterIdentifier", id)
                        .formParam("SkipFinalSnapshot", "true").post("/");
            }
            rds("DeleteDBClusterSnapshot").formParam("DBClusterSnapshotIdentifier", snapshot).post("/");
        }
    }
}
