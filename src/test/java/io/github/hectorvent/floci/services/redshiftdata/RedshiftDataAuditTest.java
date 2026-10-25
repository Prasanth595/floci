package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import io.github.hectorvent.floci.services.redshift.RedshiftAuditLogPublisher;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedshiftDataAuditTest {

    @Test
    void commentedUserDdlProducesUserAndActivityRecords() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.execute("/* migration */ CREATE /* change */ USER alice PASSWORD 'secret'");
            assertTrue(fixture.records("userlog").stream().anyMatch(line -> line.contains(",alice,,Create,")),
                    fixture.events.toString());
            assertTrue(fixture.records("useractivitylog").stream().allMatch(line -> !line.contains("secret")));
        }
    }

    @Test
    void batchPublishesAttemptedSqlAndUserChangesOnlyAfterCommit() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.batch("CREATE USER alice PASSWORD 'secret'", "SELECT 1");
            assertEquals(2, fixture.records("useractivitylog").size());
            assertEquals(1, fixture.records("userlog").size());
            fixture.events.clear();

            fixture.batch("CREATE USER bob PASSWORD 'secret'", "SELECT * FROM missing", "SELECT 3");
            assertEquals(2, fixture.records("useractivitylog").size());
            assertTrue(fixture.records("userlog").isEmpty(), fixture.events.toString());
        }
    }

    @Test
    void retainedSessionPublishesUserChangesOnCommitAndDiscardsThemOnRollback() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("BEGIN")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            fixture.reuse(session, "CREATE USER alice PASSWORD 'secret'");
            assertTrue(fixture.records("userlog").isEmpty());
            fixture.reuse(session, "COMMIT");
            assertEquals(1, fixture.records("userlog").size(), fixture.events.toString());
            fixture.events.clear();
            fixture.reuse(session, "BEGIN");
            fixture.reuse(session, "CREATE USER bob PASSWORD 'secret'");
            fixture.reuse(session, "ROLLBACK");
            assertTrue(fixture.records("userlog").isEmpty());
            fixture.reuse(session, "BEGIN");
            fixture.reuse(session, "CREATE USER carol PASSWORD 'secret'");
            fixture.reuse(session, "SELECT * FROM missing");
            fixture.reuse(session, "COMMIT");
            assertTrue(fixture.records("userlog").isEmpty());
        }
    }

    @Test
    void retainedConnectionLogsAuthenticationOnceAndDisconnectionWhenRetired() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String session = fixture.service.executeStatement(fixture.request("SELECT 1")
                    .put("SessionKeepAliveSeconds", 60), "us-east-1").path("SessionId").asText();
            fixture.reuse(session, "SELECT 2");
            assertEquals(1, fixture.records("connectionlog").size(), fixture.events.toString());
            assertTrue(fixture.records("connectionlog").getFirst().startsWith("authenticated,"));
            fixture.service.clear();
            assertEquals(2, fixture.records("connectionlog").size());
            assertTrue(fixture.records("connectionlog").getLast().startsWith("disconnection,"));
        }
    }

    @Test
    void failedBackendAuthenticationProducesAConnectionRecord() throws Exception {
        try (Fixture fixture = new Fixture()) {
            when(fixture.factory.open(any())).thenThrow(new SQLException("bad password", "28P01"));
            fixture.execute("SELECT 1");
            assertEquals(1, fixture.records("connectionlog").size());
            assertTrue(fixture.records("connectionlog").getFirst().startsWith("authentication failure,"));
        }
    }

    @Test
    void activityAndConnectionRecordsUseRequestedIdentityInsteadOfBackendCredentials() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.execute("SELECT 1");
            assertTrue(fixture.records("useractivitylog").getFirst().contains(",analytics,analyst,"));
            assertTrue(fixture.records("connectionlog").stream().allMatch(line -> line.contains(",analytics,analyst,")));
        }
    }

    private record Event(String group, String message) { }

    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
        private final RedshiftDataExecutor executor = new RedshiftDataExecutor(2, 8, 1);
        private final RedshiftDataService service;
        private final Connection keeper;

        private Fixture() throws Exception {
            String url = "jdbc:h2:mem:" + UUID.randomUUID();
            keeper = DriverManager.getConnection(url);
            when(factory.open(any())).thenAnswer(invocation -> DriverManager.getConnection(url));
            Cluster cluster = new Cluster();
            cluster.setClusterIdentifier("warehouse");
            cluster.setLoggingEnabled(true);
            cluster.setLoggingDestinationType("cloudwatch");
            cluster.setUserActivityLoggingEnabled(true);
            RedshiftDataResourceResolver resolver = mock(RedshiftDataResourceResolver.class);
            when(resolver.resolve(any(), any())).thenReturn(new RedshiftDataResourceResolver.DatabaseTarget(
                    "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse", "localhost", 5439,
                    "analytics", "admin", "password", null, cluster, "analyst"));
            CloudWatchLogsService logs = mock(CloudWatchLogsService.class);
            doAnswer(invocation -> {
                List<Map<String, Object>> records = invocation.getArgument(3);
                for (Map<String, Object> record : records) {
                    events.add(new Event(invocation.getArgument(1), record.get("message").toString()));
                }
                return null;
            }).when(logs).putLogEventsForAccount(anyString(), anyString(), anyString(), anyList(), anyString());
            service = new RedshiftDataService(resolver, factory, new RedshiftDataStatementStore(24, Clock.systemUTC()),
                    mapper, null, executor, null, null, null,
                    new RedshiftDataIdempotencyStore(AccountAwareStorageBackend.inMemory("000000000000"), Clock.systemUTC()),
                    new RedshiftDataSessions(), new RedshiftAuditLogPublisher(logs));
        }

        private List<String> records(String type) {
            return events.stream().filter(event -> event.group().endsWith("/" + type)).map(Event::message).toList();
        }

        private ObjectNode request(String sql) {
            return mapper.createObjectNode().put("Sql", sql).put("WaitTimeSeconds", 5)
                    .put("ClusterIdentifier", "warehouse").put("Database", "analytics").put("DbUser", "analyst");
        }

        private void execute(String sql) {
            service.executeStatement(request(sql), "us-east-1");
        }

        private void reuse(String session, String sql) {
            service.executeStatement(mapper.createObjectNode().put("Sql", sql).put("SessionId", session)
                    .put("WaitTimeSeconds", 5), "us-east-1");
        }

        private void batch(String... sqls) {
            ObjectNode request = request("unused");
            request.remove("Sql");
            ArrayNode statements = request.putArray("Sqls");
            for (String sql : sqls) {
                statements.add(sql);
            }
            service.batchExecuteStatement(request, "us-east-1");
        }

        @Override
        public void close() throws SQLException {
            service.clear();
            executor.close();
            keeper.close();
        }
    }
}
