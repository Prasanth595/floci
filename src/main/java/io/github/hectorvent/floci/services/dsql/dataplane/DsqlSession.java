package io.github.hectorvent.floci.services.dsql.dataplane;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * One client connection to a DSQL endpoint, as Aurora DSQL serves it (measured on AWS, eu-west-1,
 * 2026-10-16): TLS is mandatory, the password is an IAM token for the endpoint, the startup is read
 * as DSQL reads it, and a connection lives an hour.
 */
final class DsqlSession {

    static final Duration CONNECTION_LIFETIME = Duration.ofHours(1);
    private static final String ACCESS_DENIED = "unable to accept connection, access denied";

    private final Socket socket;
    private final DsqlEndpoint endpoint;
    private final String sessionId = DsqlIdentifiers.newId();
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
        PgWire.Startup first = PgWire.readStartup(rawIn);
        if (first.code() == PgWire.CANCEL_REQUEST) {
            endpoint.forwardCancel(first.body());
            return;
        }
        if (first.code() == PgWire.GSSENC_REQUEST) {
            rawOut.write('N');
            rawOut.flush();
            first = PgWire.readStartup(rawIn);
        }
        if (first.code() != PgWire.SSL_REQUEST) {
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
        PgWire.Startup startup = PgWire.readStartup(in);
        if (startup.code() == PgWire.CANCEL_REQUEST) {
            endpoint.forwardCancel(startup.body());
            return;
        }
        if (startup.code() != PgWire.PROTOCOL_3) {
            refuse(out, "08P01", "unsupported frontend protocol", null);
            return;
        }
        Map<String, String> startupParameters = PgWire.startupParameters(startup.body());
        String user = startupParameters.getOrDefault("user", "");
        String database = startupParameters.getOrDefault("database", user);

        PgWire.write(out, (byte) 'R', PgWire.int32(3));
        out.flush();
        PgWire.Message password = PgWire.read(in);
        if (password.type() != 'p') {
            refuse(out, "08006", ACCESS_DENIED, null);
            return;
        }
        String token = PgWire.cString(password.body(), 0).value();
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
            backendOut.write(PgWire.startupMessage(backendStartup));
            backendOut.flush();

            Thread toClient = Thread.ofVirtual().name("dsql-backend-" + sessionId)
                    .start(() -> relayToClient(backendIn, out, target, tls));
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

    private static void relayToBackend(InputStream in, OutputStream backendOut) throws IOException {
        while (true) {
            PgWire.Message message = PgWire.read(in);
            PgWire.write(backendOut, message);
            if (message.type() == 'X') {
                backendOut.flush();
                return;
            }
            if (in.available() == 0) {
                backendOut.flush();
            }
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

    private void relayToClient(InputStream backendIn, OutputStream out, DsqlDatabases.Backend backend, Socket client) {
        try {
            PgWire.Message authenticated = null;
            boolean started = false;
            while (true) {
                PgWire.Message message = PgWire.read(backendIn);
                if (!started) {
                    // DSQL refuses a connection instead of authenticating it, so the database's
                    // AuthenticationOk waits for what follows it, and a refusal takes its place.
                    if (message.type() == 'R' && authenticated == null) {
                        authenticated = message;
                        continue;
                    }
                    if (message.type() == 'E') {
                        message = new PgWire.Message(message.type(), withSessionId(message.body()));
                    } else if (authenticated != null) {
                        PgWire.write(out, authenticated);
                    }
                    authenticated = null;
                    started = message.type() == 'Z';
                }
                if (message.type() == 'K') {
                    cancelKey = endpoint.registerCancelKey(message.body(), backend);
                }
                PgWire.write(out, message);
                if (backendIn.available() == 0) {
                    out.flush();
                }
            }
        } catch (IOException e) {
            closeQuietly(client);
        }
    }

    /**
     * A refusal of the database's as DSQL words every one: the session's id as its detail, and no
     * source location.
     */
    private byte[] withSessionId(byte[] body) {
        Map<Character, String> fields = PgWire.parseFields(body);
        fields.remove('F');
        fields.remove('L');
        fields.remove('R');
        fields.putIfAbsent('D', "Session Id: " + sessionId);
        return PgWire.fields(fields);
    }

    /** A refusal as DSQL words every one: FATAL, with the session's id as its detail. */
    private void refuse(OutputStream out, String state, String message, String hint) throws IOException {
        PgWire.write(out, (byte) 'E', PgWire.error("FATAL", state, message, "Session Id: " + sessionId, hint));
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
