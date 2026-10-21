package io.github.hectorvent.floci.services.dsql.dataplane;

import io.github.hectorvent.floci.core.common.PostgresWire;
import io.github.hectorvent.floci.services.dsql.DsqlIdentifiers;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.StandardConstants;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * One client connection to a DSQL endpoint, as Aurora DSQL serves it (measured on AWS, eu-west-1,
 * 2026-10-16): TLS is mandatory, the password is an IAM token for the endpoint, the startup is read
 * as DSQL reads it, DSQL's own statements become the {@code sys} calls that implement them, their
 * errors come back without the source locations and {@code sys} lines DSQL never shows, and a
 * connection lives an hour.
 */
final class DsqlSession {

    static final Duration CONNECTION_LIFETIME = Duration.ofHours(1);
    // A startup packet is refused above PostgreSQL's own limit, 10,000 bytes, and a message above 64 MB.
    private static final int MAX_STARTUP = 10_000;
    private static final int MAX_MESSAGE = 64 * 1024 * 1024;
    private static final String ACCESS_DENIED = "unable to accept connection, access denied";

    private final Socket socket;
    private final DsqlEndpoint endpoint;
    private final String sessionId = DsqlIdentifiers.newId();
    // One batch per Query or Sync sent, oldest first, holding what the database's answers to it
    // settle, in the order it answers them. A batch goes with its ReadyForQuery, and with it what an
    // error left unanswered, so a later batch's are never touched.
    private final Deque<Batch> batches = new ArrayDeque<>();
    private Batch openBatch;
    // The prepared statements DSQL tags its own way, and the statement each portal runs, as the
    // database confirmed them. Only the thread relaying the database's answers touches them.
    private final Map<String, String> statementTags = new HashMap<>();
    private final Map<String, String> portalStatements = new HashMap<>();
    private volatile String principalArn;
    private volatile int backendPid;
    private volatile long cancelKey;

    DsqlSession(Socket socket, DsqlEndpoint endpoint) {
        this.socket = socket;
        this.endpoint = endpoint;
    }

    void serve() throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(endpoint.handshakeTimeoutMillis());
        InputStream rawIn = socket.getInputStream();
        OutputStream rawOut = socket.getOutputStream();
        PostgresWire.Startup first = PostgresWire.readStartup(rawIn, MAX_STARTUP, "startup message");
        if (first.code() == PostgresWire.CANCEL_REQUEST) {
            endpoint.forwardCancel(first.body());
            return;
        }
        if (first.code() == PostgresWire.GSSENC_REQUEST) {
            rawOut.write('N');
            rawOut.flush();
            first = PostgresWire.readStartup(rawIn, MAX_STARTUP, "startup message");
        }
        if (first.code() != PostgresWire.SSL_REQUEST) {
            refuse(rawOut, "08P01", "unable to accept connection, SSL is mandatory. AWS Authentication is required.",
                    null);
            return;
        }
        rawOut.write('S');
        rawOut.flush();

        String[] serverName = new String[1];
        SSLSocket tls = (SSLSocket) endpoint.sslContext().getSocketFactory().createSocket(socket, null, true);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setSNIMatchers(List.of(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
            @Override
            public boolean matches(SNIServerName name) {
                if (name instanceof SNIHostName host) {
                    serverName[0] = host.getAsciiName().toLowerCase(Locale.ROOT);
                }
                return true;
            }
        }));
        tls.setSSLParameters(parameters);
        tls.setUseClientMode(false);
        tls.startHandshake();

        InputStream in = new BufferedInputStream(tls.getInputStream());
        OutputStream out = new BufferedOutputStream(tls.getOutputStream());
        PostgresWire.Startup startup = PostgresWire.readStartup(in, MAX_STARTUP, "startup message");
        if (startup.code() == PostgresWire.CANCEL_REQUEST) {
            endpoint.forwardCancel(startup.body());
            return;
        }
        if (startup.code() != PostgresWire.PROTOCOL_3) {
            refuse(out, "08P01", "unsupported frontend protocol", null);
            return;
        }
        Map<String, String> startupParameters = PostgresWire.startupParameters(startup.body());
        String user = startupParameters.getOrDefault("user", "");
        String database = startupParameters.getOrDefault("database", user);

        PostgresWire.write(out, (byte) 'R', PostgresWire.int32(3));
        out.flush();
        PostgresWire.Message password = PostgresWire.read(in, MAX_MESSAGE, "message");
        if (password.type() != 'p') {
            refuse(out, "08006", ACCESS_DENIED, null);
            return;
        }
        String token = PostgresWire.cString(password.body(), 0).value();
        DsqlEndpoint.Admission admission = endpoint.admit(token, user, serverName[0]);
        if (!admission.allowed()) {
            refuse(out, admission.state(), ACCESS_DENIED, admission.hint());
            return;
        }
        if (!"postgres".equals(database)) {
            refuse(out, "55000", "database \"" + database + "\" is not currently accepting connections", null);
            return;
        }
        Map<String, String> optionSettings = optionSettings(startupParameters.get("options"));
        String custom = customParameter(optionSettings, startupParameters);
        if (custom != null) {
            refuse(out, "42704", "unrecognized configuration parameter \"" + custom + "\"", null);
            return;
        }

        DsqlDatabases.Backend target = admission.route().backend();
        endpoint.opened(admission.route(), tls);
        Socket backend = new Socket();
        try (backend) {
            backend.connect(new InetSocketAddress(target.host(), target.port()), 5_000);
            backend.setTcpNoDelay(true);
            socket.setSoTimeout(0);
            InputStream backendIn = new BufferedInputStream(backend.getInputStream());
            OutputStream backendOut = new BufferedOutputStream(backend.getOutputStream());
            Map<String, String> backendStartup = new LinkedHashMap<>(startupParameters);
            backendStartup.remove("options");
            if (!optionSettings.isEmpty()) {
                backendStartup.put("options", backendOptions(optionSettings));
            }
            backendStartup.put("database", "postgres");
            principalArn = admission.principalArn();
            backendOut.write(PostgresWire.startupMessage(backendStartup));
            backendOut.flush();

            Thread toClient = Thread.ofVirtual().name("dsql-backend-" + sessionId)
                    .start(() -> relayToClient(backendIn, out, admission.route(), tls));
            Thread lifetime = Thread.ofVirtual().name("dsql-lifetime-" + sessionId).start(() -> {
                try {
                    Thread.sleep(CONNECTION_LIFETIME.toMillis());
                    closeQuietly(tls);
                    closeQuietly(backend);
                } catch (InterruptedException ignored) {
                    // The connection ended first.
                }
            });
            try {
                relayToBackend(in, backendOut);
            } finally {
                lifetime.interrupt();
                closeQuietly(backend);
                toClient.interrupt();
            }
        } finally {
            endpoint.closed(admission.route(), tls);
            if (cancelKey != 0) {
                endpoint.forgetCancelKey(cancelKey);
            }
        }
    }

    private void relayToBackend(InputStream in, OutputStream backendOut) throws IOException {
        while (true) {
            PostgresWire.Message message = PostgresWire.read(in, MAX_MESSAGE, "message");
            switch (message.type()) {
                case 'Q' -> {
                    String query = PostgresWire.cString(message.body(), 0).value();
                    PostgresWire.write(backendOut, 'Q', PostgresWire.cStringBytes(rewriteSimpleQuery(query)));
                }
                case 'P' -> {
                    PostgresWire.CString name = PostgresWire.cString(message.body(), 0);
                    PostgresWire.CString query = PostgresWire.cString(message.body(), name.next());
                    DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(query.value());
                    String tag = rewritten.overridesTags() && rewritten.commandTags().size() == 1
                            ? rewritten.commandTags().get(0) : null;
                    synchronized (batches) {
                        extendedBatch().parses.addLast(new Named(name.value(), tag));
                    }
                    PostgresWire.write(backendOut, 'P', PostgresWire.cStringBytes(name.value()),
                            PostgresWire.cStringBytes(rewritten.sql()),
                            Arrays.copyOfRange(message.body(), query.next(), message.body().length));
                }
                case 'B' -> {
                    PostgresWire.CString portal = PostgresWire.cString(message.body(), 0);
                    PostgresWire.CString statement = PostgresWire.cString(message.body(), portal.next());
                    synchronized (batches) {
                        extendedBatch().binds.addLast(new Named(portal.value(), statement.value()));
                    }
                    PostgresWire.write(backendOut, message);
                }
                case 'E' -> {
                    String portal = PostgresWire.cString(message.body(), 0).value();
                    synchronized (batches) {
                        extendedBatch().results.addLast(new Named(portal, null));
                    }
                    PostgresWire.write(backendOut, message);
                }
                case 'C' -> {
                    String kind = message.body()[0] == 'S' ? "S" : "P";
                    synchronized (batches) {
                        extendedBatch().closes.addLast(new Named(PostgresWire.cString(message.body(), 1).value(), kind));
                    }
                    PostgresWire.write(backendOut, message);
                }
                case 'S', 'F' -> {
                    // A Sync, or a FunctionCall, is answered by a ReadyForQuery of its own.
                    synchronized (batches) {
                        extendedBatch();
                        openBatch = null;
                    }
                    PostgresWire.write(backendOut, message);
                }
                case 'X' -> {
                    PostgresWire.write(backendOut, message);
                    backendOut.flush();
                    return;
                }
                default -> PostgresWire.write(backendOut, message);
            }
            if (in.available() == 0) {
                backendOut.flush();
            }
        }
    }

    /**
     * A simple query as the database runs it. Its statements' command tags join the batch its
     * ReadyForQuery ends: the extended-protocol messages sent since the last Sync, if any.
     */
    private String rewriteSimpleQuery(String query) {
        DsqlStatements.Query rewritten = DsqlStatements.rewriteQuery(query);
        synchronized (batches) {
            Batch batch = extendedBatch();
            rewritten.commandTags().forEach(tag -> batch.results.addLast(new Named(null, tag)));
            openBatch = null;
        }
        return rewritten.sql();
    }

    /** The batch the extended-protocol messages since the last Sync belong to. Called holding {@code batches}. */
    private Batch extendedBatch() {
        if (openBatch == null) {
            openBatch = new Batch();
            batches.addLast(openBatch);
        }
        return openBatch;
    }

    /**
     * What one Query or Sync's messages wait for the database to settle, each kind in the order the
     * database answers it: the statements parsed and their tags, the portals bound and the statements
     * they run, the statements and portals closed, and the results, each a portal's or a simple
     * query statement's tag.
     */
    private static final class Batch {
        final Deque<Named> parses = new ArrayDeque<>();
        final Deque<Named> binds = new ArrayDeque<>();
        final Deque<Named> closes = new ArrayDeque<>();
        final Deque<Named> results = new ArrayDeque<>();
    }

    /** A statement or portal name, and what goes with it; a simple query's result has no name. */
    private record Named(String name, String value) {
    }

    /** The answer of the oldest batch the database is answering, from {@code queue}. */
    private Named settle(Function<Batch, Deque<Named>> queue) {
        synchronized (batches) {
            Batch batch = batches.peekFirst();
            return batch == null ? null : queue.apply(batch).pollFirst();
        }
    }

    /**
     * The settings a client's startup {@code options} make, as DSQL reads them (measured, eu-west-1,
     * 2026-10-20): words split on white space, with no escapes, of which only {@code -c name=value}
     * counts, the first for a name winning. DSQL ignores every other word, {@code --name=value}
     * included, so none of them may reach the database.
     */
    static Map<String, String> optionSettings(String options) {
        Map<String, String> settings = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        String[] words = options == null ? new String[0] : options.strip().split("\\s+");
        for (int i = 0; i + 1 < words.length; i++) {
            if ("-c".equals(words[i])) {
                String setting = words[++i];
                int equals = setting.indexOf('=');
                if (equals > 0 && seen.add(setting.substring(0, equals).toLowerCase(Locale.ROOT))) {
                    settings.put(setting.substring(0, equals), setting.substring(equals + 1));
                }
            }
        }
        return settings;
    }

    /**
     * The first custom (dotted) parameter a startup sets, in its {@code options} or on its own: DSQL
     * refuses the connection for one (measured, eu-west-1, 2026-10-20), {@code dsql.} or not.
     */
    static String customParameter(Map<String, String> optionSettings, Map<String, String> startupParameters) {
        return Stream.concat(optionSettings.keySet().stream(), startupParameters.keySet().stream())
                .filter(name -> name.contains(".")).findFirst().orElse(null);
    }

    /** {@code settings} as PostgreSQL's {@code options}, whose backslash escapes the next character. */
    static String backendOptions(Map<String, String> settings) {
        List<String> words = new ArrayList<>();
        settings.forEach((name, value) -> words.add("-c " + (name + "=" + value).replace("\\", "\\\\")));
        return String.join(" ", words);
    }

    private void relayToClient(InputStream backendIn, OutputStream out, DsqlEndpoint.Route route, Socket client) {
        try {
            List<PostgresWire.Message> startup = new ArrayList<>();
            boolean started = false;
            while (true) {
                PostgresWire.Message message = PostgresWire.read(backendIn, MAX_MESSAGE, "message");
                if (!started) {
                    // DSQL refuses a connection instead of authenticating it, so what the database
                    // answers the startup waits for its ReadyForQuery, and a refusal takes its place.
                    switch (message.type()) {
                        case 'E' -> {
                            startup.clear();
                            message = new PostgresWire.Message(message.type(), withSessionId(message.body()));
                        }
                        case 'Z' -> {
                            // The session's identity is in place before the client's first statement.
                            if (!endpoint.recordSession(route.backend(), backendPid, principalArn, sessionId)) {
                                refuse(out, "08006", ACCESS_DENIED, null);
                                closeQuietly(client);
                                return;
                            }
                            for (PostgresWire.Message answer : startup) {
                                PostgresWire.write(out, answer);
                            }
                            started = true;
                        }
                        case 'K' -> {
                            cancelKey = endpoint.registerCancelKey(message.body(), route.backend());
                            backendPid = PostgresWire.int32(message.body(), 0);
                            startup.add(message);
                            continue;
                        }
                        default -> {
                            startup.add(message);
                            continue;
                        }
                    }
                }
                switch (message.type()) {
                    case 'C' -> {
                        Named result = settle(batch -> batch.results);
                        String override = result == null ? null : result.name() == null ? result.value()
                                : statementTags.get(portalStatements.get(result.name()));
                        if (override != null) {
                            message = new PostgresWire.Message((byte) 'C', PostgresWire.cStringBytes(override));
                        }
                    }
                    case 'I', 's' -> settle(batch -> batch.results);
                    case '1' -> {
                        Named parsed = settle(batch -> batch.parses);
                        if (parsed != null && parsed.value() != null) {
                            statementTags.put(parsed.name(), parsed.value());
                        } else if (parsed != null) {
                            statementTags.remove(parsed.name());
                        }
                    }
                    case '2' -> {
                        Named bound = settle(batch -> batch.binds);
                        if (bound != null) {
                            portalStatements.put(bound.name(), bound.value());
                        }
                    }
                    case '3' -> {
                        Named closed = settle(batch -> batch.closes);
                        if (closed != null) {
                            (closed.value().equals("S") ? statementTags : portalStatements).remove(closed.name());
                        }
                    }
                    case 'E', 'N' -> message = new PostgresWire.Message(message.type(),
                            inDsqlWords(message.type(), message.body()));
                    case 'Z' -> {
                        synchronized (batches) {
                            batches.pollFirst();
                        }
                        if (message.body()[0] == 'I') {
                            // Outside a transaction no portal outlives its Sync.
                            portalStatements.clear();
                        }
                    }
                    default -> {
                    }
                }
                PostgresWire.write(out, message);
                if (backendIn.available() == 0) {
                    out.flush();
                }
            }
        } catch (IOException ignored) {
            // The database or the client ended the connection: the client's side is closed with it.
            closeQuietly(client);
        }
    }

    /**
     * An error or notice as DSQL words it (measured on AWS). An error carries no source location,
     * PostgreSQL's own included, while a notice keeps its location. The context keeps what the
     * client's SQL ran and drops what Floci's {@code sys} functions ran, as DSQL's context never
     * shows its own: the context lists the innermost call first, so everything up to the last
     * line of a {@code sys} function, the statements it ran included, is theirs.
     */
    static byte[] inDsqlWords(byte type, byte[] body) {
        Map<Character, String> fields = PostgresWire.parseFields(body, 0);
        boolean changed = false;
        String where = fields.get('W');
        if (where != null) {
            List<String> lines = where.lines().toList();
            int ownLines = 0;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("function sys.")) {
                    ownLines = i + 1;
                }
            }
            String kept = String.join("\n", lines.subList(ownLines, lines.size()));
            if (!kept.equals(where)) {
                changed = true;
                if (kept.isEmpty()) {
                    fields.remove('W');
                } else {
                    fields.put('W', kept);
                }
            }
        }
        if (type == 'E') {
            changed |= fields.remove('F') != null;
            changed |= fields.remove('L') != null;
            changed |= fields.remove('R') != null;
        }
        return changed ? PostgresWire.fields(fields) : body;
    }

    /** A refusal of the database's as DSQL words every one: the session's id as its detail. */
    private byte[] withSessionId(byte[] body) {
        Map<Character, String> fields = PostgresWire.parseFields(body, 0);
        fields.putIfAbsent('D', "Session Id: " + sessionId);
        return PostgresWire.fields(fields);
    }

    /** An ErrorResponse body of {@code severity} (FATAL, ERROR), as DSQL orders its fields. */
    static byte[] error(String severity, String state, String message, String detail, String hint) {
        Map<Character, String> fields = new LinkedHashMap<>();
        fields.put('S', severity);
        fields.put('V', severity);
        fields.put('C', state);
        fields.put('M', message);
        if (detail != null) {
            fields.put('D', detail);
        }
        if (hint != null) {
            fields.put('H', hint);
        }
        return PostgresWire.fields(fields);
    }

    /** A refusal as DSQL words every one: FATAL, with the session's id as its detail. */
    private void refuse(OutputStream out, String state, String message, String hint) throws IOException {
        PostgresWire.write(out, (byte) 'E', error("FATAL", state, message, "Session Id: " + sessionId, hint));
        out.flush();
    }

    private static void closeQuietly(Socket closing) {
        try {
            closing.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }
}
