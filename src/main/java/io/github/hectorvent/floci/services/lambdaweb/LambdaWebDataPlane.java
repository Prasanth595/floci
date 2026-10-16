package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.RequestHost;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.Environment;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebEnvironmentManager.StartFailure;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService.WebFunctionTarget;
import io.github.hectorvent.floci.services.lambdaweb.model.WebFunctionRevision;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Serves the endpoint domains of Web Functions. A request whose Host is a {@code lambda-web} domain
 * goes over HTTP/1.1 to the revision's environment with the headers AWS adds and drops; any other
 * request falls through to the next route.
 */
@ApplicationScoped
public class LambdaWebDataPlane {

    static final Set<String> SUPPORTED_METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "OPTIONS", "DELETE");
    /** Dropped before forwarding. {@code te} passes through, as recorded. */
    static final Set<String> DROPPED_REQUEST_HEADERS = Set.of("host", "connection", "keep-alive", "upgrade", "expect",
            "trailer", "proxy-authenticate", "proxy-authorization", "proxy-connection", "x-real-ip",
            "x-http-method-override", "x-http-method", "x-method-override");
    static final Set<String> DROPPED_RESPONSE_HEADERS = Set.of("connection", "keep-alive", "transfer-encoding",
            "upgrade", "te", "trailer", "trailers", "proxy-authenticate", "proxy-authorization");
    static final String REQUEST_ID_HEADER = "lambda-web-request-id";
    private static final Logger LOG = Logger.getLogger(LambdaWebDataPlane.class);
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int REQUEST_ID_LENGTH = 26;
    private static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final LambdaWebService service;
    private final LambdaWebEnvironmentManager environments;
    private final ServiceConfigAccess serviceConfigAccess;
    private final Vertx vertx;
    private final HttpClient client;

    @Inject
    public LambdaWebDataPlane(LambdaWebService service, LambdaWebEnvironmentManager environments,
                              ServiceConfigAccess serviceConfigAccess, Vertx vertx) {
        this.service = service;
        this.environments = environments;
        this.serviceConfigAccess = serviceConfigAccess;
        this.vertx = vertx;
        this.client = vertx.createHttpClient(new HttpClientOptions()
                .setMaxPoolSize(256)
                .setConnectTimeout(5_000)
                .setKeepAlive(false));
    }

    void register(@Observes Router router) {
        router.route().handler(this::handle);
    }

    void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        String host = RequestHost.of(request);
        if (host == null || !isLambdaWebHost(host) || !serviceConfigAccess.isEnabled("lambda")) {
            context.next();
            return;
        }
        String requestId = newRequestId();
        if (request.version() == HttpVersion.HTTP_1_0) {
            page(request, LambdaWebErrorPages.UNSUPPORTED_HTTP_VERSION, requestId, true);
            return;
        }
        if (!SUPPORTED_METHODS.contains(request.method().name())) {
            malformed(request);
            return;
        }
        Optional<WebFunctionTarget> located = service.locate(host);
        if (located.isEmpty()) {
            page(request, LambdaWebErrorPages.INVALID_ENDPOINT, requestId, false);
            return;
        }
        WebFunctionTarget target = located.get();
        if (target.endpoint().throttleConfig != null
                && Integer.valueOf(0).equals(target.endpoint().throttleConfig.rateLimit)) {
            page(request, LambdaWebErrorPages.ENDPOINT_DISABLED, requestId, false);
            return;
        }
        WebFunctionRevision revision = LambdaWebService.pickRevision(target);
        if (revision == null) {
            page(request, LambdaWebErrorPages.ORIGIN_REQUEST_ERROR, requestId, false);
            return;
        }
        if ("100-continue".equalsIgnoreCase(request.getHeader("Expect"))) {
            request.response().writeContinue();
        }
        request.pause();
        acquire(request, host, target, revision, requestId, null);
    }

    /** Starts or reuses the revision's environment off the event loop, then forwards. */
    private void acquire(HttpServerRequest request, String host, WebFunctionTarget target,
                         WebFunctionRevision revision, String requestId, Environment dead) {
        vertx.<Environment>executeBlocking(() -> {
            if (dead != null) {
                environments.discard(dead);
            }
            return environments.acquire(target.accountId(), target.region(), target.function(), revision);
        }, false)
                .onSuccess(environment -> forward(request, host, target, revision, environment, requestId, dead == null))
                .onFailure(error -> {
                    request.resume();
                    if (error instanceof StartFailure failure) {
                        page(request, failure.page(), requestId, false);
                        return;
                    }
                    LOG.errorv(error, "Could not start an environment for {0}", host);
                    internalError(request, requestId);
                });
    }

    private void forward(HttpServerRequest request, String host, WebFunctionTarget target,
                         WebFunctionRevision revision, Environment environment, String requestId, boolean firstTry) {
        environment.begin();
        Instant started = Instant.now();
        AtomicBoolean finished = new AtomicBoolean();
        boolean hasBody = hasRequestBody(request);
        RequestOptions options = new RequestOptions()
                .setHost(environment.host())
                .setPort(environment.port())
                .setURI(request.uri())
                .setMethod(request.method());
        client.request(options).onSuccess(upstream -> {
            copyRequestHeaders(request, upstream, host, target.endpoint().endpointName, requestId);
            upstream.idleTimeout(TimeUnit.SECONDS.toMillis(revision.serviceConfig.timeoutSeconds));
            upstream.response().onSuccess(response -> relay(request, response, environment, requestId, finished))
                    .onFailure(error -> vertx.runOnContext(ignored -> {
                        LOG.debugv("The environment {0} did not answer request {1}: {2}",
                                environment.containerId(), requestId, error.toString());
                        HttpServerResponse out = request.response();
                        if (out.headWritten()) {
                            out.close();
                            finish(environment, finished);
                        } else if (error instanceof TimeoutException) {
                            page(request, LambdaWebErrorPages.REQUEST_TIMEOUT, requestId, false);
                            finish(environment, finished);
                        } else {
                            failed(request, host, target, revision, environment, requestId, started,
                                    firstTry && !hasBody, finished);
                        }
                    }));
            if (hasBody) {
                upstream.send(request);
                request.resume();
            } else {
                upstream.end();
            }
        }).onFailure(error -> {
            LOG.debugv("The environment {0} refused the connection: {1}", environment.containerId(), error.toString());
            failed(request, host, target, revision, environment, requestId, started, firstTry, finished);
        });
    }

    /**
     * A process that exited before the request arrived is invisible to the client, which gets a
     * fresh environment, as on AWS. One that died under the request, or that cannot be retried
     * because the body is spent, fails the request.
     */
    private void failed(HttpServerRequest request, String host, WebFunctionTarget target,
                        WebFunctionRevision revision, Environment environment, String requestId, Instant started,
                        boolean retry, AtomicBoolean finished) {
        finish(environment, finished);
        vertx.<Boolean>executeBlocking(() -> environments.exitedBefore(environment, started), false)
                .onComplete(exited -> {
                    if (retry && exited.succeeded() && exited.result()) {
                        acquire(request, host, target, revision, requestId, environment);
                        return;
                    }
                    request.resume();
                    page(request, LambdaWebErrorPages.ORIGIN_REQUEST_ERROR, requestId, false);
                });
    }

    static boolean hasRequestBody(HttpServerRequest request) {
        String length = request.getHeader("Content-Length");
        return (length != null && !"0".equals(length.trim())) || request.headers().contains("Transfer-Encoding");
    }

    private void relay(HttpServerRequest request, HttpClientResponse response, Environment environment,
                       String requestId, AtomicBoolean finished) {
        HttpServerResponse out = request.response();
        out.setStatusCode(response.statusCode());
        copyResponseHeaders(response.headers(), out);
        out.headers().set("lambda-web-logstream", environment.logStream());
        out.headers().set(REQUEST_ID_HEADER, requestId);
        if (response.getHeader("Content-Length") == null && hasBody(request.method(), response.statusCode())) {
            out.setChunked(true);
        }
        response.exceptionHandler(error -> {
            if (out.headWritten()) {
                out.close();
            } else {
                page(request, LambdaWebErrorPages.ORIGIN_REQUEST_ERROR, requestId, false);
            }
        });
        response.pipeTo(out).onComplete(ignored -> finish(environment, finished));
    }

    private static void finish(Environment environment, AtomicBoolean finished) {
        if (finished.compareAndSet(false, true)) {
            environment.end();
        }
    }

    static boolean isLambdaWebHost(String host) {
        return host.toLowerCase(Locale.ROOT).contains(".lambda-web.");
    }

    static boolean hasBody(HttpMethod method, int status) {
        return method != HttpMethod.HEAD && status >= 200 && status != 204 && status != 304;
    }

    static void copyRequestHeaders(HttpServerRequest request, HttpClientRequest upstream, String host,
                                   String endpointName, String requestId) {
        Set<String> dropped = new HashSet<>(DROPPED_REQUEST_HEADERS);
        for (String connection : request.headers().getAll("Connection")) {
            for (String token : connection.split(",")) {
                dropped.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        MultiMap headers = upstream.headers();
        for (Map.Entry<String, String> header : request.headers()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!dropped.contains(name) && !name.startsWith("lambda-web-")) {
                headers.add(header.getKey(), header.getValue());
            }
        }
        headers.set("Host", host);
        headers.add("X-Forwarded-For", request.remoteAddress() == null ? "" : request.remoteAddress().host());
        headers.set("x-forwarded-proto", request.scheme());
        headers.set("x-forwarded-port", String.valueOf(port(host, request.scheme())));
        headers.set(REQUEST_ID_HEADER, requestId);
        headers.set("lambda-web-endpoint-name", endpointName);
        headers.set("Connection", "close");
    }

    static void copyResponseHeaders(MultiMap headers, HttpServerResponse out) {
        Set<String> dropped = new HashSet<>(DROPPED_RESPONSE_HEADERS);
        for (String connection : headers.getAll("Connection")) {
            for (String token : connection.split(",")) {
                dropped.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        for (Map.Entry<String, String> header : headers) {
            if (!dropped.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                out.headers().add(header.getKey(), header.getValue());
            }
        }
    }

    static int port(String host, String scheme) {
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(']') < colon) {
            try {
                return Integer.parseInt(host.substring(colon + 1));
            } catch (NumberFormatException e) {
                return "https".equals(scheme) ? 443 : 80;
            }
        }
        return "https".equals(scheme) ? 443 : 80;
    }

    static String newRequestId() {
        StringBuilder id = new StringBuilder(REQUEST_ID_LENGTH);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < REQUEST_ID_LENGTH; i++) {
            id.append(BASE32.charAt(random.nextInt(BASE32.length())));
        }
        return id.toString();
    }

    static void page(HttpServerRequest request, LambdaWebErrorPages.Page page, String requestId, boolean close) {
        HttpServerResponse response = request.response();
        response.setStatusCode(page.status()).setStatusMessage(page.reason());
        response.headers().set("content-type", "text/html");
        response.headers().set("connection", close ? "close" : "keep-alive");
        response.headers().set(REQUEST_ID_HEADER, requestId);
        response.headers().set("lambda-web-error-type", page.errorType());
        response.headers().set("date", HTTP_DATE.format(Instant.now()));
        response.end(LambdaWebErrorPages.html(page, requestId)).onComplete(ignored -> {
            if (close) {
                response.close();
            }
        });
    }

    /** The edge refuses a method before the platform sees the request, with its own header casing. */
    static void malformed(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        response.setStatusCode(400).setStatusMessage("Bad Request");
        response.headers().set("Date", LambdaWebErrorPages.edgeDate(Instant.now()));
        response.headers().set("Connection", "close");
        response.headers().set("x-amz-request-id", Long.toHexString(ThreadLocalRandom.current().nextLong())
                .toUpperCase(Locale.ROOT));
        response.headers().set("Content-Type", "text/html; charset=utf-8");
        response.headers().set("Lambda-Web-Error-Type", LambdaWebErrorPages.MALFORMED_REQUEST_TYPE);
        response.end(LambdaWebErrorPages.MALFORMED_REQUEST_HTML).onComplete(ignored -> response.close());
    }

    /** AWS answers its own failures with 502 and no error type, so nothing is revealed. */
    private static void internalError(HttpServerRequest request, String requestId) {
        HttpServerResponse response = request.response();
        response.setStatusCode(502);
        response.headers().set(REQUEST_ID_HEADER, requestId);
        response.end();
    }
}
