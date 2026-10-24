package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RedshiftDataSessionsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RedshiftDataSessions.Scope OWNER = new RedshiftDataSessions.Scope("000000000000", "us-east-1", "caller");
    private static final RedshiftDataResourceResolver.DatabaseTarget TARGET = new RedshiftDataResourceResolver.DatabaseTarget(
            "arn:aws:redshift:us-east-1:000000000000:cluster:warehouse", "localhost", 5439, "dev", "admin", "password");

    @Test
    void clearClosesAConnectionThatFinishesOpeningAfterReset() throws Exception {
        try (RedshiftDataSessions sessions = new RedshiftDataSessions(Clock.systemUTC());
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Connection connection = mock(Connection.class);
            CountDownLatch opening = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
            when(factory.open(any())).thenAnswer(invocation -> {
                opening.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return connection;
            });
            RedshiftDataSessions.Reservation reservation = sessions.reserve(OWNER, request().put("SessionKeepAliveSeconds", 10), TARGET);
            reservation.admitted();
            Future<Connection> late = worker.submit(() -> reservation.connection(factory));
            try {
                assertTrue(opening.await(5, TimeUnit.SECONDS));
                sessions.clear();
                release.countDown();
                assertThrows(ExecutionException.class, () -> late.get(5, TimeUnit.SECONDS));
                verify(connection).close();
                assertThrows(AwsException.class, () -> sessions.find(OWNER, reservation.id()));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void idleDeadlineStartsAfterCompletionAndUnspecifiedTimeoutIsRetained() throws Exception {
        MutableClock clock = new MutableClock();
        try (RedshiftDataSessions sessions = new RedshiftDataSessions(clock)) {
            Connection connection = mock(Connection.class);
            RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
            when(factory.open(any())).thenReturn(connection);
            RedshiftDataSessions.Reservation first = sessions.reserve(OWNER, request().put("SessionKeepAliveSeconds", 10), TARGET);
            first.admitted();
            first.connection(factory);
            clock.advance(Duration.ofSeconds(20));
            sessions.sweep();
            verify(connection, never()).close();
            first.finish();
            clock.advance(Duration.ofSeconds(9));
            ObjectNode reuse = MAPPER.createObjectNode().put("SessionId", first.id());
            RedshiftDataSessions.Reservation next = sessions.reserve(OWNER, reuse, TARGET);
            next.admitted();
            assertSame(connection, next.connection(factory));
            next.finish();
            clock.advance(Duration.ofSeconds(9));
            assertNotNull(sessions.find(OWNER, first.id()));
            clock.advance(Duration.ofSeconds(1));
            sessions.sweep();
            verify(connection).close();
            assertThrows(AwsException.class, () -> sessions.find(OWNER, first.id()));
        }
    }

    @Test
    void busySessionsRejectNewWorkAndFailedAdmissionDoesNotExtendIdleTime() throws Exception {
        MutableClock clock = new MutableClock();
        try (RedshiftDataSessions sessions = new RedshiftDataSessions(clock)) {
            RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
            when(factory.open(any())).thenReturn(mock(Connection.class));
            RedshiftDataSessions.Reservation first = sessions.reserve(OWNER, request().put("SessionKeepAliveSeconds", 10), TARGET);
            first.admitted();
            first.connection(factory);
            ObjectNode reuse = MAPPER.createObjectNode().put("SessionId", first.id());
            assertThrows(AwsException.class, () -> sessions.reserve(OWNER, reuse, TARGET));
            first.finish();
            clock.advance(Duration.ofSeconds(9));
            RedshiftDataSessions.Reservation rejected = sessions.reserve(OWNER, reuse.put("SessionKeepAliveSeconds", 100), TARGET);
            rejected.abort();
            clock.advance(Duration.ofSeconds(1));
            assertThrows(AwsException.class, () -> sessions.find(OWNER, first.id()));
        }
    }

    @Test
    void sessionsAreScopedToAccountRegionAndCaller() throws Exception {
        try (RedshiftDataSessions sessions = new RedshiftDataSessions(Clock.systemUTC())) {
            RedshiftDataSessions.Reservation first = sessions.reserve(OWNER, request().put("SessionKeepAliveSeconds", 10), TARGET);
            for (RedshiftDataSessions.Scope foreign : new RedshiftDataSessions.Scope[]{
                    new RedshiftDataSessions.Scope("111111111111", "us-east-1", "caller"),
                    new RedshiftDataSessions.Scope("000000000000", "us-west-2", "caller"),
                    new RedshiftDataSessions.Scope("000000000000", "us-east-1", "another-caller")}) {
                assertThrows(AwsException.class, () -> sessions.find(foreign, first.id()));
            }
            assertNotNull(sessions.find(OWNER, first.id()));
            first.abort();
        }
    }

    @Test
    void zeroTimeoutClosesTheSessionAfterItsLastQuery() throws Exception {
        try (RedshiftDataSessions sessions = new RedshiftDataSessions(Clock.systemUTC())) {
            Connection connection = mock(Connection.class);
            RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
            when(factory.open(any())).thenReturn(connection);
            RedshiftDataSessions.Reservation first = sessions.reserve(OWNER, request().put("SessionKeepAliveSeconds", 10), TARGET);
            first.admitted();
            first.connection(factory);
            first.finish();
            RedshiftDataSessions.Reservation last = sessions.reserve(OWNER,
                    MAPPER.createObjectNode().put("SessionId", first.id()).put("SessionKeepAliveSeconds", 0), TARGET);
            last.admitted();
            last.connection(factory);
            last.finish();
            verify(connection).close();
            assertThrows(AwsException.class, () -> sessions.find(OWNER, first.id()));
        }
    }

    @Test
    void absoluteDeadlineClosesAnActiveConnectionAndCannotBeExtended() throws Exception {
        MutableClock clock = new MutableClock();
        try (RedshiftDataSessions sessions = new RedshiftDataSessions(clock)) {
            Connection connection = mock(Connection.class);
            RedshiftDataConnectionFactory factory = mock(RedshiftDataConnectionFactory.class);
            when(factory.open(any())).thenReturn(connection);
            RedshiftDataSessions.Reservation first = sessions.reserve(OWNER, request().put("SessionKeepAliveSeconds", 86400), TARGET);
            first.admitted();
            RedshiftDataExecution execution = new RedshiftDataExecution(0);
            first.bind(execution);
            first.connection(factory);
            clock.advance(Duration.ofHours(24));
            sessions.sweep();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(connection).close());
            execution.completed();
            assertThrows(AwsException.class, () -> sessions.find(OWNER, first.id()));
        }
    }

    @Test
    void sessionLimitIsSharedAcrossCallersAndAbortedReservationsReleaseCapacity() {
        try (RedshiftDataSessions sessions = new RedshiftDataSessions(Clock.systemUTC())) {
            RedshiftDataSessions.Reservation first = sessions.reserve(OWNER, request().put("SessionKeepAliveSeconds", 10), TARGET);
            for (int n = 1; n < 500; n++) {
                sessions.reserve(new RedshiftDataSessions.Scope("000000000000", "us-east-1", "caller-" + n),
                        request().put("SessionKeepAliveSeconds", 10), TARGET);
            }
            AwsException full = assertThrows(AwsException.class, () -> sessions.reserve(OWNER,
                    request().put("SessionKeepAliveSeconds", 10), TARGET));
            assertEquals("ActiveSessionsExceededException", full.getErrorCode());
            first.abort();
            assertNotNull(sessions.reserve(OWNER, request().put("SessionKeepAliveSeconds", 10), TARGET));
        }
    }

    private static ObjectNode request() {
        return MAPPER.createObjectNode().put("ClusterIdentifier", "warehouse").put("Database", "dev").put("DbUser", "admin");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-24T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
