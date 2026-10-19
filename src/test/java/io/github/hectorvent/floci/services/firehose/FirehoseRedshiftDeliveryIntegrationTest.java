package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end: a record put into a Firehose stream with a Redshift destination is staged in S3 and
 * loaded into the cluster table through COPY. Needs a Docker daemon, like every Redshift
 * integration test.
 */
@QuarkusTest
class FirehoseRedshiftDeliveryIntegrationTest {

    @Inject
    FirehoseService firehoseService;

    @Inject
    RedshiftService redshiftService;

    @Inject
    ObjectMapper mapper;

    private String clusterId;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(),
                "Docker daemon must be available for Firehose Redshift delivery integration tests");
    }

    @AfterEach
    void cleanUp() {
        if (clusterId != null) {
            redshiftService.deleteCluster(clusterId);
        }
    }

    private static boolean isDockerAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Connection waitForConnection(Cluster cluster) throws SQLException {
        String url = "jdbc:postgresql://127.0.0.1:" + cluster.getEndpoint().getPort() + "/dev";
        try {
            return Awaitility.await()
                    .atMost(Duration.ofSeconds(30))
                    .pollDelay(Duration.ZERO)
                    .pollInterval(Duration.ofMillis(500))
                    .ignoreExceptions()
                    .until(() -> DriverManager.getConnection(url, "admin", "Secret123"), Objects::nonNull);
        } catch (ConditionTimeoutException e) {
            return DriverManager.getConnection(url, "admin", "Secret123");
        }
    }

    private RedshiftDestination destination(String clusterName, String table, String stageBucket) throws Exception {
        return mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://%s.abc123.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "%s", "CopyOptions": "json 'auto'"},
                 "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                                     "BucketARN": "arn:aws:s3:::%s"}}
                """.formatted(clusterName, table, stageBucket), RedshiftDestination.class);
    }

    @Test
    void putRecordIsLoadedIntoTheRedshiftTable() throws Exception {
        clusterId = "fh-rs-e2e";
        Cluster cluster = redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        try (Connection connection = waitForConnection(cluster); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fh_events (id int, name text)");
        }
        firehoseService.createDeliveryStream("us-east-1", "fh-to-redshift", null,
                destination(clusterId, "fh_events", "fh-rs-stage"), List.of(), null, null);
        firehoseService.putRecord("fh-to-redshift",
                new Record("{\"id\":1,\"name\":\"alice\"}".getBytes(StandardCharsets.UTF_8)));

        firehoseService.flush("fh-to-redshift");

        try (Connection connection = waitForConnection(cluster);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id, name FROM fh_events")) {
            assertTrue(rows.next(), "expected the delivered row in fh_events");
            assertEquals(1, rows.getInt("id"));
            assertEquals("alice", rows.getString("name"));
            assertFalse(rows.next());
        }
    }

    @Test
    void missingTableKeepsTheStagedObjectAndTheRecord() throws Exception {
        clusterId = "fh-rs-no-table";
        redshiftService.createCluster(clusterId, "dc2.large", "admin", "Secret123");
        firehoseService.createDeliveryStream("us-east-1", "fh-no-table", null,
                destination(clusterId, "does_not_exist", "fh-rs-no-table-stage"), List.of(), null, null);
        firehoseService.putRecord("fh-no-table",
                new Record("{\"id\":1,\"name\":\"alice\"}".getBytes(StandardCharsets.UTF_8)));

        firehoseService.flush("fh-no-table");

        String listing = given().when().get("/fh-rs-no-table-stage").then().statusCode(200).extract().asString();
        assertTrue(listing.contains("<Key>"), "expected the staged object, got: " + listing);
    }
}
