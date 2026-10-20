package io.github.hectorvent.floci.services.rds;

import com.github.dockerjava.api.DockerClient;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A parameter group's overrides are the engine's configuration: an Aurora MySQL cluster runs with
 * its DB cluster parameter group with its writer's DB parameter group on top, a standalone MySQL
 * instance with its DB parameter group, and a value changed in the group, or a group switched by
 * ModifyDBCluster or ModifyDBInstance, takes effect when the database restarts. Skipped without
 * Docker.
 */
@QuarkusTest
@Tag("docker")
class RdsParameterGroupEngineIntegrationTest {

    private static final String MASTER_USER = "admin";
    private static final String MASTER_PASSWORD = "Passw0rd12345";

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void checkDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
    }

    @Test
    void auroraMysqlClusterRunsWithItsClusterParameterGroup() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "engine-cpg-" + suffix;
        String clusterId = "engine-cpg-cluster-" + suffix;
        rds("CreateDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", group)
                .formParam("DBParameterGroupFamily", "aurora-mysql8.0")
                .formParam("Description", "engine parameters")
        .when().post("/").then().statusCode(200);
        try {
            rds("ModifyDBClusterParameterGroup")
                    .formParam("DBClusterParameterGroupName", group)
                    .formParam("Parameters.member.1.ParameterName", "max_connections")
                    .formParam("Parameters.member.1.ParameterValue", "500")
                    .formParam("Parameters.member.1.ApplyMethod", "immediate")
                    .formParam("Parameters.member.2.ParameterName", "slow_query_log")
                    .formParam("Parameters.member.2.ParameterValue", "1")
                    .formParam("Parameters.member.2.ApplyMethod", "immediate")
                    .formParam("Parameters.member.3.ParameterName", "long_query_time")
                    .formParam("Parameters.member.3.ParameterValue", "1")
                    .formParam("Parameters.member.3.ApplyMethod", "immediate")
                    // Exists only on Aurora: the engine must still start.
                    .formParam("Parameters.member.4.ParameterName", "aurora_binlog_replication_max_yield_seconds")
                    .formParam("Parameters.member.4.ParameterValue", "0")
                    .formParam("Parameters.member.4.ApplyMethod", "immediate")
                    // A formula is resolved by AWS from the instance class, so it is left out.
                    .formParam("Parameters.member.5.ParameterName", "innodb_buffer_pool_size")
                    .formParam("Parameters.member.5.ParameterValue", "{DBInstanceClassMemory*3/4}")
                    .formParam("Parameters.member.5.ApplyMethod", "pending-reboot")
            .when().post("/").then().statusCode(200);

            XmlPath created = rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-mysql")
                    .formParam("DBClusterParameterGroupName", group)
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
            .when().post("/").then().statusCode(200).extract().xmlPath();
            int port = created.getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");

            assertThat(query(port, "SELECT @@GLOBAL.max_connections, @@GLOBAL.slow_query_log, "
                    + "@@GLOBAL.long_query_time"), equalTo("500 1 1.0"));

            String reported = rds("DescribeDBClusters")
                    .formParam("DBClusterIdentifier", clusterId)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("DescribeDBClustersResponse.DescribeDBClustersResult.DBClusters"
                            + ".DBCluster.DBClusterParameterGroup");
            assertThat(reported, equalTo(group));

            rds("ModifyDBClusterParameterGroup")
                    .formParam("DBClusterParameterGroupName", group)
                    .formParam("Parameters.member.1.ParameterName", "max_connections")
                    .formParam("Parameters.member.1.ParameterValue", "400")
                    .formParam("Parameters.member.1.ApplyMethod", "pending-reboot")
            .when().post("/").then().statusCode(200);
            rds("RebootDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
            .when().post("/").then().statusCode(200);

            assertThat(query(port, "SELECT @@GLOBAL.max_connections"), equalTo("400"));
        } finally {
            rds("DeleteDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds("DeleteDBClusterParameterGroup")
                    .formParam("DBClusterParameterGroupName", group)
            .when().post("/");
        }
    }

    @Test
    void auroraMysqlWriterRunsWithItsDbParameterGroupOverTheClusterGroup() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String clusterGroup = "engine-wcpg-" + suffix;
        String switchedClusterGroup = "engine-wcpg2-" + suffix;
        String writerGroup = "engine-wpg-" + suffix;
        String clusterId = "engine-w-cluster-" + suffix;
        String writerId = "engine-w-writer-" + suffix;
        createClusterParameterGroup(clusterGroup, "max_connections", "500", "slow_query_log", "1");
        createClusterParameterGroup(switchedClusterGroup, "max_connections", "450", "slow_query_log", "0");
        createParameterGroup(writerGroup, "aurora-mysql8.0", "max_connections", "800");
        try {
            int port = rds("CreateDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-mysql")
                    .formParam("DBClusterParameterGroupName", clusterGroup)
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBClusterResponse.CreateDBClusterResult.DBCluster.Port");
            assertThat(query(port, "SELECT @@GLOBAL.max_connections, @@GLOBAL.slow_query_log"),
                    equalTo("500 1"));

            rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", writerId)
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("Engine", "aurora-mysql")
                    .formParam("DBInstanceClass", "db.r6g.large")
                    .formParam("DBParameterGroupName", writerGroup)
            .when().post("/").then().statusCode(200);
            assertThat(query(port, "SELECT @@GLOBAL.max_connections, @@GLOBAL.slow_query_log"),
                    equalTo("800 1"));

            String reported = rds("ModifyDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("DBClusterParameterGroupName", switchedClusterGroup)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("ModifyDBClusterResponse.ModifyDBClusterResult.DBCluster.DBClusterParameterGroup");
            assertThat(reported, equalTo(switchedClusterGroup));
            rds("RebootDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
            .when().post("/").then().statusCode(200);
            assertThat(query(port, "SELECT @@GLOBAL.max_connections, @@GLOBAL.slow_query_log"),
                    equalTo("800 0"));
        } finally {
            rds("DeleteDBInstance")
                    .formParam("DBInstanceIdentifier", writerId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            rds("DeleteDBCluster")
                    .formParam("DBClusterIdentifier", clusterId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            for (String group : new String[] {clusterGroup, switchedClusterGroup}) {
                rds("DeleteDBClusterParameterGroup")
                        .formParam("DBClusterParameterGroupName", group)
                .when().post("/");
            }
            rds("DeleteDBParameterGroup")
                    .formParam("DBParameterGroupName", writerGroup)
            .when().post("/");
        }
    }

    @Test
    void mysqlInstanceRunsWithItsDbParameterGroup() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String group = "engine-pg-" + suffix;
        String switchedGroup = "engine-pg2-" + suffix;
        String instanceId = "engine-pg-db-" + suffix;
        createParameterGroup(group, "mysql8.0", "max_connections", "300");
        createParameterGroup(switchedGroup, "mysql8.0", "max_connections", "600");
        try {

            int port = rds("CreateDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("Engine", "mysql")
                    .formParam("EngineVersion", "8.0")
                    .formParam("DBInstanceClass", "db.t3.micro")
                    .formParam("AllocatedStorage", "20")
                    .formParam("DBParameterGroupName", group)
                    .formParam("MasterUsername", MASTER_USER)
                    .formParam("MasterUserPassword", MASTER_PASSWORD)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");

            assertThat(query(port, "SELECT @@GLOBAL.max_connections"), equalTo("300"));

            String reported = rds("ModifyDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("DBParameterGroupName", switchedGroup)
            .when().post("/").then().statusCode(200).extract().xmlPath()
                    .getString("ModifyDBInstanceResponse.ModifyDBInstanceResult.DBInstance"
                            + ".DBParameterGroups.DBParameterGroup.DBParameterGroupName");
            assertThat(reported, equalTo(switchedGroup));
            rds("RebootDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
            .when().post("/").then().statusCode(200);
            assertThat(query(port, "SELECT @@GLOBAL.max_connections"), equalTo("600"));
        } finally {
            rds("DeleteDBInstance")
                    .formParam("DBInstanceIdentifier", instanceId)
                    .formParam("SkipFinalSnapshot", "true")
            .when().post("/");
            for (String name : new String[] {group, switchedGroup}) {
                rds("DeleteDBParameterGroup")
                        .formParam("DBParameterGroupName", name)
                .when().post("/");
            }
        }
    }

    private static void createParameterGroup(String name, String family, String parameter, String value) {
        rds("CreateDBParameterGroup")
                .formParam("DBParameterGroupName", name)
                .formParam("DBParameterGroupFamily", family)
                .formParam("Description", "engine parameters")
        .when().post("/").then().statusCode(200);
        rds("ModifyDBParameterGroup")
                .formParam("DBParameterGroupName", name)
                .formParam("Parameters.member.1.ParameterName", parameter)
                .formParam("Parameters.member.1.ParameterValue", value)
                .formParam("Parameters.member.1.ApplyMethod", "immediate")
        .when().post("/").then().statusCode(200);
    }

    private static void createClusterParameterGroup(
            String name, String parameter, String value, String secondParameter, String secondValue) {
        rds("CreateDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", name)
                .formParam("DBParameterGroupFamily", "aurora-mysql8.0")
                .formParam("Description", "engine parameters")
        .when().post("/").then().statusCode(200);
        rds("ModifyDBClusterParameterGroup")
                .formParam("DBClusterParameterGroupName", name)
                .formParam("Parameters.member.1.ParameterName", parameter)
                .formParam("Parameters.member.1.ParameterValue", value)
                .formParam("Parameters.member.1.ApplyMethod", "immediate")
                .formParam("Parameters.member.2.ParameterName", secondParameter)
                .formParam("Parameters.member.2.ParameterValue", secondValue)
                .formParam("Parameters.member.2.ApplyMethod", "immediate")
        .when().post("/").then().statusCode(200);
    }

    /**
     * The first row of a query, its columns joined by single spaces. The MySQL image restarts its
     * server once after initializing a new data directory, so a refused connection is retried.
     */
    private static String query(int port, String sql) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            try {
                return queryOnce(port, sql);
            } catch (SQLException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }

    private static String queryOnce(int port, String sql) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", MASTER_USER);
        properties.setProperty("password", MASTER_PASSWORD);
        properties.setProperty("useSSL", "false");
        properties.setProperty("allowPublicKeyRetrieval", "true");
        // A server still initializing can accept a connection and never answer it: give up and retry.
        properties.setProperty("connectTimeout", "10000");
        properties.setProperty("socketTimeout", "10000");
        try (Connection connection = DriverManager.getConnection(
                     "jdbc:mysql://127.0.0.1:" + port + "/", properties);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            StringBuilder row = new StringBuilder();
            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                if (column > 1) {
                    row.append(' ');
                }
                row.append(rows.getString(column));
            }
            return row.toString();
        }
    }
}
