package io.github.hectorvent.floci.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.dsql.dataplane.DsqlEndpoint;
import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Aurora DSQL's data plane against a real PostgreSQL container: a client connects to the cluster
 * endpoint with TLS and an IAM token, with the refusals measured on AWS (eu-west-1, 2026-10-16).
 */
@QuarkusTest
@TestProfile(DsqlDatabasesProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlDataPlaneIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261016/" + REGION + "/dsql/aws4_request, SignedHeaders=host, Signature=abc";

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private String identifier;
    private String host;

    @BeforeAll
    void createCluster() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
        identifier = dsql().body(Map.of("deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).extract().path("identifier");
        host = identifier + ".dsql." + REGION + ".localhost.floci.io";
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(500)).until(() ->
                "ACTIVE".equals(dsql().get("/cluster/" + identifier).then().extract().path("status")));
    }

    @AfterAll
    void deleteCluster() {
        if (identifier != null) {
            dsql().delete("/cluster/" + identifier);
            await().atMost(Duration.ofMinutes(1)).until(() ->
                    dsql().get("/cluster/" + identifier).then().extract().statusCode() == 404);
        }
    }

    @Test
    void adminConnectsWithAnAdminTokenToDsqlsDatabase() throws Exception {
        try (Connection connection = connect("admin", adminToken());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_user, current_setting('transaction_isolation'),"
                     + " (SELECT nspowner::regrole::text FROM pg_namespace WHERE nspname = 'public')")) {
            assertTrue(row.next());
            assertEquals("admin", row.getString(1));
            assertEquals("repeatable read", row.getString(2));
            assertEquals("admin", row.getString(3));
        }
    }

    @Test
    void aConnectionIsRefusedAsDsqlRefusesIt() throws Exception {
        PSQLException plain = assertThrows(PSQLException.class, () -> {
            Properties properties = properties("admin", adminToken());
            properties.setProperty("sslmode", "disable");
            DriverManager.getConnection(url(), properties).close();
        });
        assertEquals("unable to accept connection, SSL is mandatory. AWS Authentication is required.",
                plain.getServerErrorMessage().getMessage());
        assertEquals("08P01", plain.getSQLState());
        assertTrue(plain.getServerErrorMessage().getDetail().startsWith("Session Id: "));

        PSQLException wrongAction = assertThrows(PSQLException.class, () ->
                connect("admin", token("DbConnect", "test", "test", Instant.now(), 900, null)).close());
        assertEquals("unable to accept connection, access denied", wrongAction.getServerErrorMessage().getMessage());
        assertEquals("08006", wrongAction.getSQLState());
        assertTrue(wrongAction.getServerErrorMessage().getDetail().startsWith("Session Id: "));
        assertEquals("Wrong user to action mapping. user: admin, action: DbConnect",
                wrongAction.getServerErrorMessage().getHint());

        PSQLException expired = assertThrows(PSQLException.class, () -> connect("admin",
                token("DbConnectAdmin", "test", "test", Instant.now().minusSeconds(120), 60, null)).close());
        assertTrue(expired.getServerErrorMessage().getHint().startsWith("Signature expired: "),
                expired.getServerErrorMessage().getHint());

        PSQLException garbage = assertThrows(PSQLException.class, () -> connect("admin", "not-a-token").close());
        assertEquals("unable to accept connection, access denied", garbage.getServerErrorMessage().getMessage());
        assertEquals(null, garbage.getServerErrorMessage().getHint());

        PSQLException otherDatabase = assertThrows(PSQLException.class, () -> {
            Properties properties = properties("admin", adminToken());
            DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + endpoint.port() + "/other", properties)
                    .close();
        });
        assertEquals("database \"other\" is not currently accepting connections",
                otherDatabase.getServerErrorMessage().getMessage());
        assertEquals("55000", otherDatabase.getSQLState());
    }

    /** A role other than {@code admin} signs in only for an IAM role mapped to it, and none is. */
    @Test
    void aRoleOtherThanAdminIsRefusedAsAnUnmappedRole() throws Exception {
        try (Connection admin = connect("admin", adminToken()); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE app_role WITH LOGIN");
        }

        PSQLException unmapped = assertThrows(PSQLException.class, () ->
                connect("app_role", token("DbConnect", "test", "test", Instant.now(), 900, null)).close());
        assertEquals("28000", unmapped.getSQLState());
        assertEquals("unable to accept connection, access denied", unmapped.getServerErrorMessage().getMessage());
        assertTrue(unmapped.getServerErrorMessage().getDetail().startsWith("Session Id: "));

        PSQLException adminAction = assertThrows(PSQLException.class, () ->
                connect("app_role", token("DbConnectAdmin", "test", "test", Instant.now(), 900, null)).close());
        assertEquals("08006", adminAction.getSQLState());
        assertEquals("Wrong user to action mapping. user: app_role, action: DbConnectAdmin",
                adminAction.getServerErrorMessage().getHint());
    }

    @Test
    void aStartupSettingIsReadAsDsqlReadsIt() throws Exception {
        for (String name : List.of("dsql.iam_session_user", "myapp.foo")) {
            Properties custom = properties("admin", adminToken());
            custom.setProperty("options", "-c " + name + "=x");
            PSQLException refused = assertThrows(PSQLException.class, () ->
                    DriverManager.getConnection(url(), custom).close());
            assertEquals("42704", refused.getSQLState());
            assertEquals("unrecognized configuration parameter \"" + name + "\"",
                    refused.getServerErrorMessage().getMessage());
            assertTrue(refused.getServerErrorMessage().getDetail().startsWith("Session Id: "));
        }

        Properties unknown = properties("admin", adminToken());
        unknown.setProperty("options", "-c nosuch_parameter=1");
        PSQLException fromTheDatabase = assertThrows(PSQLException.class, () ->
                DriverManager.getConnection(url(), unknown).close());
        assertEquals("42704", fromTheDatabase.getSQLState());
        assertEquals("unrecognized configuration parameter \"nosuch_parameter\"",
                fromTheDatabase.getServerErrorMessage().getMessage());
        assertTrue(fromTheDatabase.getServerErrorMessage().getDetail().startsWith("Session Id: "));
        assertEquals(null, fromTheDatabase.getServerErrorMessage().getFile());

        Properties ignored = properties("admin", adminToken());
        ignored.setProperty("options", "--search_path=elsewhere -c search_path=first -c search_path=second");
        try (Connection connection = DriverManager.getConnection(url(), ignored);
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_setting('search_path')")) {
            assertTrue(row.next());
            assertEquals("first", row.getString(1));
        }
    }

    /**
     * A long-term key signs in to its own account's clusters and to no other account's, whichever
     * account is Floci's default: the cluster here is not in the default one.
     */
    @Test
    void aLongTermKeySignsInOnlyToItsOwnAccountsClusters() throws Exception {
        EnforcementFixtures.UserCredentials own = accessKey(ACCOUNT);
        EnforcementFixtures.UserCredentials other = accessKey("000000000000");

        connect("admin", token("DbConnectAdmin", own.accessKeyId(), own.secretAccessKey(), Instant.now(), 900,
                null)).close();
        PSQLException foreign = assertThrows(PSQLException.class, () -> connect("admin", token("DbConnectAdmin",
                other.accessKeyId(), other.secretAccessKey(), Instant.now(), 900, null)).close());
        assertEquals("08006", foreign.getSQLState());
        assertEquals("unable to accept connection, access denied", foreign.getServerErrorMessage().getMessage());
    }

    /** A new IAM user's access key in {@code account}, removed with the user after the test. */
    private EnforcementFixtures.UserCredentials accessKey(String account) {
        return EnforcementFixtures.createUser(cleanup, httpPort, EnforcementFixtures.auth(account, "iam"),
                "dsql-" + System.nanoTime());
    }

    private Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(url(), properties(user, password));
    }

    private String url() {
        return "jdbc:postgresql://127.0.0.1:" + endpoint.port() + "/postgres";
    }

    private static Properties properties(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "require");
        properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        return properties;
    }

    private String adminToken() throws Exception {
        return token("DbConnectAdmin", "test", "test", Instant.now(), 900, null);
    }

    private String token(String action, String accessKeyId, String secret, Instant at, int expires,
                         String sessionToken) throws Exception {
        return SigV4TokenTestHelper.createDsqlToken(host, REGION, action, accessKeyId, secret, at, expires,
                sessionToken);
    }

    private RequestSpecification dsql() {
        return given().port(httpPort).header("Authorization", AUTH).contentType("application/json");
    }
}
