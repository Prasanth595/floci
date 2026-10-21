package io.github.hectorvent.floci.services.dsql.dataplane;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.dsql.DsqlDatabasesProfile;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A transaction lives 300 seconds on DSQL, ended as DSQL ends it (measured, eu-west-1, 2026-10-21):
 * the client's next message after the limit, a ROLLBACK included, gets the age error and the
 * connection closes; a transaction idle past the limit and 15 seconds more is ended the same way
 * unasked; a statement that runs across the limit ends in the age error, which fails the
 * transaction, and a failed transaction idle as long is closed. The limit is one second here, and
 * the grace one second more.
 */
@QuarkusTest
@TestProfile(DsqlDatabasesProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlTransactionAgeIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261016/" + REGION + "/dsql/aws4_request, SignedHeaders=host, Signature=abc";
    private static final Duration LIMIT = Duration.ofSeconds(1);
    private static final Duration GRACE = Duration.ofSeconds(1);

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

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
        endpoint.transactionAgeLimit(LIMIT, GRACE);
    }

    @AfterEach
    void keepTheShortLimit() {
        endpoint.transactionAgeLimit(LIMIT, GRACE);
    }

    @AfterAll
    void deleteCluster() {
        endpoint.transactionAgeLimit(Duration.ofSeconds(300), Duration.ofSeconds(15));
        if (identifier != null) {
            dsql().delete("/cluster/" + identifier);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"extended", "simple"})
    void aMessagePastTheLimitEndsTheTransactionAndTheConnection(String queryMode) throws Exception {
        try (Connection connection = connect(queryMode); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();

            Thread.sleep(1_500);
            SQLException expired = assertThrows(SQLException.class, () -> statement.execute("ROLLBACK"));

            assertMentions(expired, "transaction age limit of 1s exceeded");
            assertFalse(connection.isValid(2));
        }
    }

    @Test
    void anIdleTransactionPastTheLimitAndItsGraceIsEndedUnasked() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();

            Thread.sleep(3_000);

            SQLException ended = assertThrows(SQLException.class, () -> statement.executeQuery("SELECT 2").close());
            assertMentions(ended, "Current transaction age 2s exceeds 1s");
            assertFalse(connection.isValid(2));
        }
    }

    @Test
    void aStatementRunningPastTheLimitEndsInTheAgeErrorAndFailsTheTransaction() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();

            SQLException outlived = assertThrows(SQLException.class, () ->
                    statement.executeQuery("SELECT pg_sleep(1.5)").close());
            assertMentions(outlived, "transaction age limit of 1s exceeded");

            connection.rollback();
            connection.setAutoCommit(true);
            try (ResultSet row = statement.executeQuery("SELECT 3")) {
                assertTrue(row.next());
                assertEquals(3, row.getInt(1));
            }
        }
    }

    @Test
    void aFailedTransactionIdlePastTheLimitAndItsGraceIsClosed() throws Exception {
        try (Connection connection = connect("extended"); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();
            assertThrows(SQLException.class, () -> statement.executeQuery("SELECT pg_sleep(1.5)").close());

            Thread.sleep(3_000);

            SQLException closed = assertThrows(SQLException.class, () -> statement.execute("ROLLBACK"));
            assertMentions(closed, "terminating connection due to idle-in-transaction timeout");
            assertFalse(connection.isValid(2));
        }
    }

    /** A transaction a prepared BEGIN opens starts when the BEGIN runs, not when it was prepared. */
    @Test
    void aPreparedBeginStartsItsTransactionWhenItRuns() throws Exception {
        try (Connection connection = connect("extended");
             PreparedStatement begin = connection.prepareStatement("BEGIN");
             Statement statement = connection.createStatement()) {
            begin.execute();
            statement.execute("ROLLBACK");

            Thread.sleep(2_500);
            begin.execute();
            try (ResultSet row = statement.executeQuery("SELECT 1")) {
                assertTrue(row.next());
            }
            statement.execute("ROLLBACK");
        }
    }

    /** Whether {@code error} or an exception chained to it says {@code text}. */
    private static void assertMentions(SQLException error, String text) {
        List<String> messages = messages(error);
        assertTrue(messages.stream().anyMatch(message -> message.contains(text)), String.join(" | ", messages));
    }

    /**
     * The messages and details of an exception and of every exception chained to it, each once:
     * the driver can chain an exception back to one before it.
     */
    private static List<String> messages(SQLException error) {
        List<String> messages = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable each = error; each != null && seen.add(each); each = each.getCause() != null ? each.getCause()
                : each instanceof SQLException sql ? sql.getNextException() : null) {
            messages.add(String.valueOf(each.getMessage()));
            if (each instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                messages.add(psql.getSQLState() + " " + psql.getServerErrorMessage().getMessage() + " "
                        + psql.getServerErrorMessage().getDetail());
            }
        }
        return messages;
    }

    private Connection connect(String queryMode) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", "admin");
        properties.setProperty("password", SigV4TokenTestHelper.createDsqlToken(host, REGION, "DbConnectAdmin",
                "test", "test", Instant.now(), 900, null));
        properties.setProperty("sslmode", "require");
        properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        properties.setProperty("preferQueryMode", queryMode);
        properties.setProperty("prepareThreshold", "1");
        try {
            return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + endpoint.port() + "/postgres",
                    properties);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not connect to the DSQL endpoint", e);
        }
    }

    private RequestSpecification dsql() {
        return given().port(httpPort).header("Authorization", AUTH).contentType("application/json");
    }
}
