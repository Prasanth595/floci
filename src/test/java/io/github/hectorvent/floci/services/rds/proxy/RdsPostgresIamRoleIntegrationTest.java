package io.github.hectorvent.floci.services.rds.proxy;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Properties;

import static io.github.hectorvent.floci.testing.RdsRequests.rds;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@QuarkusTest
@Tag("docker")
class RdsPostgresIamRoleIntegrationTest {
    private static final String MASTER = "master";
    private static final String PASSWORD = "Master123!";
    private static final String APP_PASSWORD = "App123!";

    @Inject
    DockerClient dockerClient;

    @BeforeEach
    void requireDocker() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
    }

    @Test
    void iamLoginRequiresMembershipAndTheRolesOwnLoginAttribute() throws Exception {
        String id = uniqueId();
        try {
            int port = create(id);
            asMaster(port, "CREATE ROLE app_user LOGIN PASSWORD '" + APP_PASSWORD + "'");
            Properties token = token(port, "app_user");
            refused(port, token);
            asMaster(port, "GRANT rds_iam TO app_user");
            assertUser(port, token, "app_user");
            asMaster(port, "REVOKE rds_iam FROM app_user");
            refused(port, token);
            asMaster(port, "GRANT rds_iam TO app_user", "ALTER ROLE app_user NOLOGIN");
            refused(port, token);
            asMaster(port, "ALTER ROLE app_user LOGIN");
            assertUser(port, token, "app_user");
        } finally {
            delete(id);
        }
    }

    @Test
    void iamMembershipTakesPrecedenceOverAnApplicationPassword() throws Exception {
        String id = uniqueId();
        try {
            int port = create(id);
            asMaster(port, "CREATE ROLE app_user LOGIN PASSWORD '" + APP_PASSWORD + "'");
            Properties password = login("app_user", APP_PASSWORD);
            assertUser(port, password, "app_user");
            asMaster(port, "GRANT rds_iam TO app_user");
            refused(port, password);
            assertUser(port, token(port, "app_user"), "app_user");
            rds("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("EnableIAMDatabaseAuthentication", false).post("/").then().statusCode(200);
            assertUser(port, password, "app_user");
            rds("ModifyDBInstance").formParam("DBInstanceIdentifier", id)
                    .formParam("EnableIAMDatabaseAuthentication", true).post("/").then().statusCode(200);
            refused(port, password);
            asMaster(port, "REVOKE rds_iam FROM app_user");
            assertUser(port, password, "app_user");
        } finally {
            delete(id);
        }
    }

    @Test
    void theMasterNeedsAnActualGrantAndThenUsesIamInsteadOfItsPassword() throws Exception {
        String id = uniqueId();
        try {
            int port = create(id);
            Properties iam = token(port, MASTER);
            refused(port, iam);
            try (Connection admin = connect(port, login(MASTER, PASSWORD));
                 Statement sql = admin.createStatement()) {
                sql.execute("GRANT rds_iam TO " + MASTER);
                assertUser(port, iam, MASTER);
                refused(port, login(MASTER, PASSWORD));
                // The established administrative session remains usable after a grant.
                sql.execute("REVOKE rds_iam FROM " + MASTER);
            }
            assertUser(port, login(MASTER, PASSWORD), MASTER);
        } finally {
            delete(id);
        }
    }

    @Test
    void indirectMembershipWorksWithoutPrivilegeInheritance() throws Exception {
        String id = uniqueId();
        try {
            int port = create(id);
            asMaster(port, "CREATE ROLE iam_group NOLOGIN", "GRANT rds_iam TO iam_group",
                    "CREATE ROLE app_user LOGIN NOINHERIT PASSWORD '" + APP_PASSWORD + "'",
                    "GRANT iam_group TO app_user");
            Properties iam = token(port, "app_user");
            assertUser(port, iam, "app_user");
            refused(port, login("app_user", APP_PASSWORD));
            asMaster(port, "REVOKE rds_iam FROM iam_group");
            refused(port, iam);
            assertUser(port, login("app_user", APP_PASSWORD), "app_user");
        } finally {
            delete(id);
        }
    }

    @Test
    void roleLookupIgnoresClientEncodingAndCatalogShadows() throws Exception {
        String id = uniqueId();
        String name = "café\"reader";
        try {
            int port = create(id);
            asMaster(port, "CREATE ROLE \"café\"\"reader\" LOGIN PASSWORD '" + APP_PASSWORD + "'",
                    "GRANT rds_iam TO \"café\"\"reader\"", "CREATE SCHEMA shadow",
                    "CREATE TABLE shadow.pg_roles (oid oid, rolname name, rolcanlogin boolean)",
                    "CREATE TABLE shadow.pg_auth_members (roleid oid, member oid)",
                    "CREATE FUNCTION shadow.name_equals_text(name, text) RETURNS boolean "
                            + "LANGUAGE sql IMMUTABLE AS 'SELECT false'",
                    "CREATE OPERATOR shadow.= (LEFTARG = name, RIGHTARG = text, "
                            + "FUNCTION = shadow.name_equals_text)");
            Properties password = login(name, APP_PASSWORD);
            password.setProperty("options", "-c search_path=shadow,pg_catalog -c client_encoding=LATIN1");
            refused(port, password);
            Properties iam = token(port, name);
            iam.setProperty("options", "-c search_path=shadow,pg_catalog -c client_encoding=LATIN1");
            iam.setProperty("allowEncodingChanges", "true");
            assertUser(port, iam, name);
        } finally {
            delete(id);
        }
    }

    private static void refused(int port, Properties properties) {
        SQLException exception = assertThrows(SQLException.class, () -> connect(port, properties).close());
        assertEquals("28P01", exception.getSQLState());
    }

    private static void assertUser(int port, Properties properties, String user) throws SQLException {
        try (Connection connection = connect(port, properties);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT session_user")) {
            assertTrue(rows.next());
            assertEquals(user, rows.getString(1));
            assertFalse(rows.next());
        }
    }

    private static void asMaster(int port, String... statements) throws SQLException {
        try (Connection connection = connect(port, login(MASTER, PASSWORD));
             Statement sql = connection.createStatement()) {
            for (String statement : statements) {
                sql.execute(statement);
            }
        }
    }

    private static Properties login(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "disable");
        properties.setProperty("connectTimeout", "5");
        return properties;
    }

    private static Properties token(int port, String user) throws Exception {
        return login(user, SigV4TokenTestHelper.createRdsToken("localhost", port, user,
                "test", "test", Instant.now(), 900));
    }

    private static Connection connect(int port, Properties properties) throws SQLException {
        return DriverManager.getConnection("jdbc:postgresql://localhost:" + port + "/appdb", properties);
    }

    private static String uniqueId() {
        return "iam-role-" + Long.toString(System.nanoTime(), 36);
    }

    private static int create(String id) {
        return rds("CreateDBInstance").formParam("DBInstanceIdentifier", id).formParam("Engine", "postgres")
                .formParam("MasterUsername", MASTER).formParam("MasterUserPassword", PASSWORD)
                .formParam("DBName", "appdb").formParam("AllocatedStorage", 20)
                .formParam("DBInstanceClass", "db.t3.micro").formParam("EnableIAMDatabaseAuthentication", true)
                .post("/").then().statusCode(200).extract().xmlPath()
                .getInt("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.Endpoint.Port");
    }

    private static void delete(String id) {
        rds("DeleteDBInstance").formParam("DBInstanceIdentifier", id).formParam("SkipFinalSnapshot", true)
                .post("/").then().statusCode(200);
    }
}
