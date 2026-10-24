package io.github.hectorvent.floci.services.redshiftdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

@ApplicationScoped
class RedshiftDataSessions implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(RedshiftDataSessions.class);
    private static final Pattern SESSION_ID = Pattern.compile("[a-z0-9]{8}(-[a-z0-9]{4}){3}-[a-z0-9]{12}(:\\d{0,2})?");
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final List<String> TARGET_FIELDS = List.of("ClusterIdentifier", "WorkgroupName", "Database", "DbUser", "SecretArn");

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    private ScheduledExecutorService sweeper;
    private boolean closed;

    RedshiftDataSessions() {
        this(Clock.systemUTC());
    }

    RedshiftDataSessions(Clock clock) {
        this.clock = clock;
    }

    @PostConstruct
    void start() {
        sweeper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "redshift-data-session-sweep");
            thread.setDaemon(true);
            return thread;
        });
        sweeper.scheduleAtFixedRate(this::sweep, 1, 1, TimeUnit.SECONDS);
    }

    static void validate(JsonNode request) {
        keepAlive(request);
        JsonNode id = request.get("SessionId");
        if (id != null && (!id.isTextual() || !SESSION_ID.matcher(id.textValue()).matches())) {
            throw validation("SessionId must be a valid session identifier");
        }
    }

    private static Integer keepAlive(JsonNode request) {
        JsonNode seconds = request.get("SessionKeepAliveSeconds");
        if (seconds == null) {
            return null;
        }
        if (!seconds.isIntegralNumber() || !seconds.canConvertToInt() || seconds.intValue() < 0 || seconds.intValue() > 86400) {
            throw validation("SessionKeepAliveSeconds must be an integer between 0 and 86400");
        }
        return seconds.intValue();
    }

    Session find(Scope owner, String id) {
        Session session = sessions.get(id);
        if (session == null || !session.owner.equals(owner)) {
            throw missing();
        }
        synchronized (session) {
            if (!session.closed && !expired(session)) {
                return session;
            }
        }
        retire(session);
        throw missing();
    }

    Reservation reserve(Scope owner, JsonNode request, RedshiftDataResourceResolver.DatabaseTarget target) {
        Integer seconds = keepAlive(request);
        String id = request.has("SessionId") ? request.get("SessionId").asText() : null;
        if (id == null && (seconds == null || seconds == 0)) {
            return null;
        }
        Session session;
        boolean created = id == null;
        if (created) {
            sweep();
            synchronized (this) {
                if (closed) {
                    throw validation("Data API sessions are shutting down");
                }
                long count = sessions.values().stream().filter(existing -> existing.target.arn().equals(target.arn())
                        && existing.owner.accountId().equals(owner.accountId()) && existing.owner.region().equals(owner.region())).count();
                if (count >= 500) {
                    throw new AwsException("ActiveSessionsExceededException", "Too many active sessions for this resource", 400);
                }
                session = new Session(owner, target, request, clock.instant());
                sessions.put(session.id, session);
            }
        } else {
            session = find(owner, id);
        }
        synchronized (session) {
            if (session.closed || expired(session)) {
                throw missing();
            }
            if (session.active != null) {
                throw validation("Session is busy; wait for its current statement to finish");
            }
            Reservation reservation = new Reservation(session, created, seconds == null ? session.keepAlive : seconds);
            session.active = reservation;
            return reservation;
        }
    }

    void sweep() {
        for (Session session : sessions.values()) {
            boolean expire;
            synchronized (session) {
                expire = expired(session);
            }
            if (expire) {
                retire(session);
            }
        }
    }

    private boolean expired(Session session) {
        Instant now = clock.instant();
        return !now.isBefore(session.createdAt.plus(MAX_AGE))
                || session.active == null && !now.isBefore(session.idleUntil);
    }

    private void retire(Session session) {
        retire(session, true);
    }

    private void retire(Session session, boolean cancel) {
        Connection connection;
        RedshiftDataExecution execution;
        synchronized (session) {
            if (session.closed) {
                return;
            }
            session.closed = true;
            connection = session.connection;
            execution = cancel ? session.execution : null;
            sessions.remove(session.id, session);
        }
        Runnable cleanup = () -> {
            if (execution != null) {
                execution.cancel();
            }
            closeConnection(connection);
        };
        if (execution == null) {
            cleanup.run();
        } else {
            Thread.startVirtualThread(cleanup);
        }
    }

    private static void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException failure) {
                LOG.warnv(failure, "Unable to close a Redshift Data API session connection");
            }
        }
    }

    synchronized void clear() {
        for (Session session : sessions.values()) {
            retire(session);
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closed = true;
        if (sweeper != null) {
            sweeper.shutdownNow();
        }
        clear();
    }

    record Scope(String accountId, String region, String caller) { }

    static final class Session {
        private final String id = UUID.randomUUID().toString();
        private final Scope owner;
        private final RedshiftDataResourceResolver.DatabaseTarget target;
        private final ObjectNode parameters;
        private final Instant createdAt;
        private Instant idleUntil;
        private int keepAlive;
        private boolean closed;
        private boolean transaction;
        private boolean transactionFailed;
        private Connection connection;
        private RedshiftDataExecution execution;
        private Reservation active;

        private Session(Scope owner, RedshiftDataResourceResolver.DatabaseTarget target, JsonNode request, Instant createdAt) {
            this.owner = owner;
            this.target = target;
            this.parameters = ((ObjectNode) request).objectNode();
            for (String field : TARGET_FIELDS) {
                if (request.has(field)) {
                    parameters.set(field, request.get(field).deepCopy());
                }
            }
            this.createdAt = createdAt;
            this.idleUntil = createdAt.plus(MAX_AGE);
        }

        RedshiftDataResourceResolver.DatabaseTarget target(JsonNode request) {
            for (String field : TARGET_FIELDS) {
                JsonNode value = request.get(field);
                if (value != null && !Objects.equals(value, parameters.get(field))
                        && !("WorkgroupName".equals(field) && value.isTextual() && target.arn().equals(value.textValue()))) {
                    throw validation(field + " does not match the session");
                }
            }
            return target;
        }

        ObjectNode parameters() {
            return parameters.deepCopy();
        }
    }

    final class Reservation {
        private final Session session;
        private final boolean created;
        private final int seconds;
        private final AtomicBoolean released = new AtomicBoolean();
        private boolean admitted;

        private Reservation(Session session, boolean created, int seconds) {
            this.session = session;
            this.created = created;
            this.seconds = seconds;
        }

        String id() {
            return session.id;
        }

        void admitted() {
            admitted = true;
        }

        void bind(RedshiftDataExecution execution) throws SQLException {
            synchronized (session) {
                if (session.closed || expired(session)) {
                    throw new SQLException("Session has expired", "08003");
                }
                session.execution = execution;
            }
        }

        Connection connection(RedshiftDataConnectionFactory factory) throws SQLException {
            synchronized (session) {
                if (session.closed) {
                    throw new SQLException("Session has expired", "08003");
                }
                if (session.connection != null) {
                    return session.connection;
                }
            }
            Connection opened = factory.open(session.target);
            synchronized (session) {
                if (!session.closed && !expired(session)) {
                    session.connection = opened;
                    return opened;
                }
            }
            closeConnection(opened);
            throw new SQLException("Session has expired", "08003");
        }

        boolean inTransaction() {
            return session.transaction;
        }

        void transaction(boolean active) {
            session.transaction = active;
            if (!active) {
                session.transactionFailed = false;
            }
        }

        boolean transactionFailed() {
            return session.transactionFailed;
        }

        void failTransaction() {
            session.transactionFailed = true;
        }

        void abort() {
            release(false);
        }

        void finish() {
            release(admitted);
        }

        void discard() {
            retire(session, false);
        }

        private void release(boolean completed) {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            boolean remove;
            synchronized (session) {
                if (session.active != this) {
                    return;
                }
                session.active = null;
                session.execution = null;
                if (completed) {
                    session.keepAlive = seconds;
                    session.idleUntil = clock.instant().plusSeconds(seconds);
                }
                remove = !completed && created || completed && (seconds == 0 || session.connection == null);
            }
            if (remove) {
                retire(session);
            }
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException missing() {
        return new AwsException("ResourceNotFoundException", "Session was not found or is no longer available", 400);
    }
}
