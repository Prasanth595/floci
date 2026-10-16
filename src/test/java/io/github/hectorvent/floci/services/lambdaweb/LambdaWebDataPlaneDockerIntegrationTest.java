package io.github.hectorvent.floci.services.lambdaweb;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Requests to an endpoint domain reach a Node.js server that Floci starts from the revision's S3
 * code, with the headers AWS adds and drops, the recorded error pages and a fresh environment after
 * a crash.
 */
@QuarkusTest
class LambdaWebDataPlaneDockerIntegrationTest {

    private static final String BASE = "/2025-03-07";
    private static final String BUCKET = "web-code-docker";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final String REQUEST_ID = "[A-Z2-7]{26}";
    private static final String APP = """
            const http = require('node:http');
            const [host, port] = process.env.AWS_LAMBDA_HTTP_ENDPOINT.split(':');
            http.createServer((req, res) => {
              if (req.url.startsWith('/slow')) { setTimeout(() => res.end('late'), 8000); return; }
              if (req.url.startsWith('/stream')) {
                res.writeHead(200, {'content-type': 'text/plain'});
                let i = 0;
                const timer = setInterval(() => {
                  res.write('chunk ' + (i++) + '\\n');
                  if (i >= 10) { clearInterval(timer); res.end('done\\n'); }
                }, 500);
                return;
              }
              if (req.url.startsWith('/status/')) {
                res.writeHead(Number(req.url.split('/')[2]), {'x-app': 'yes'}); res.end('status body'); return;
              }
              if (req.url.startsWith('/crash')) { setTimeout(() => process.exit(1), 50); return; }
              let body = '';
              req.on('data', (chunk) => { body += chunk; });
              req.on('end', () => {
                res.writeHead(200, {'content-type': 'application/json', 'connection': 'keep-alive', 'trailer': 'x-t'});
                res.end(JSON.stringify({method: req.method, url: req.url, headers: req.headers, bodyLength: body.length,
                  env: {endpoint: process.env.AWS_LAMBDA_HTTP_ENDPOINT, init: process.env.AWS_LAMBDA_INITIALIZATION_TYPE,
                        exec: process.env.AWS_EXECUTION_ENV, account: process.env.AWS_ACCOUNT_ID,
                        region: process.env.AWS_REGION, custom: process.env.CUSTOM}}));
              });
            }).listen(Number(port), host);
            """;

    @Inject
    DockerClient dockerClient;

    @Inject
    S3Service s3Service;

    @Inject
    LambdaWebEnvironmentManager environments;

    private String functionName;

    @BeforeEach
    void requireDocker() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker must be available for the Lambda Web data plane");
    }

    @AfterEach
    void stopEnvironments() {
        environments.stopManagedContainers();
        if (functionName != null) {
            given().when().delete(BASE + "/web-functions/" + functionName);
        }
    }

    @Test
    @Timeout(180)
    void requestsReachTheServerWithTheHeadersAwsAddsAndDrops() throws Exception {
        String domain = createFunction(30);

        Response echo = given().header("Host", domain)
                .header("x-real-ip", "1.2.3.4")
                .header("x-http-method-override", "PUT")
                .header("lambda-web-request-id", "fake")
                .header("x-forwarded-for", "9.9.9.9")
                .header("x-custom", "1")
                .when().get("/a/b?x=1");
        echo.then().statusCode(200)
                .header("content-type", "application/json")
                .header("lambda-web-request-id", matchesPattern(REQUEST_ID))
                .header("lambda-web-logstream", matchesPattern("\\d{4}/\\d{2}/\\d{2}/" + functionName + "\\[rev-[a-z0-9]{5}\\][0-9a-f]{32}"))
                .header("trailer", nullValue())
                .body("method", equalTo("GET"))
                .body("url", equalTo("/a/b?x=1"))
                .body("headers.host", equalTo(domain))
                .body("headers.x-custom", equalTo("1"))
                .body("headers.x-real-ip", nullValue())
                .body("headers.x-http-method-override", nullValue())
                .body("headers.lambda-web-endpoint-name", equalTo("dev"))
                .body("headers.x-forwarded-proto", equalTo("http"))
                .body("headers.x-forwarded-port", equalTo("4566"))
                .body("headers.x-forwarded-for", startsWith("9.9.9.9, "))
                .body("headers.connection", equalTo("close"))
                .body("env.endpoint", equalTo("0.0.0.0:3000"))
                .body("env.init", equalTo("native-http"))
                .body("env.exec", equalTo("AWS_Lambda_nodejs24.x-lite"))
                .body("env.account", equalTo("000000000000"))
                .body("env.region", equalTo("us-east-1"))
                .body("env.custom", equalTo("yes"));
        assertEquals(echo.header("lambda-web-request-id"), echo.path("headers.lambda-web-request-id"),
                "the request id the app saw is the one in the response");

        given().header("Host", domain).body("hello body").when().post("/")
                .then().statusCode(200).body("bodyLength", equalTo(10));
        given().header("Host", domain).when().get("/status/418")
                .then().statusCode(418).header("x-app", "yes").body(equalTo("status body"));

        // A body without a Content-Length reaches the app chunked, as on AWS.
        String chunked = raw("POST /upload HTTP/1.1\r\nHost: " + domain + "\r\nTransfer-Encoding: chunked\r\n"
                + "Connection: close\r\n\r\n11\r\nchunked body here\r\n0\r\n\r\n");
        assertTrue(chunked.startsWith("HTTP/1.1 200 "), chunked);
        assertTrue(chunked.contains("\"bodyLength\":17"), chunked);
        assertTrue(chunked.contains("\"transfer-encoding\":\"chunked\""), chunked);
    }

    @Test
    @Timeout(180)
    void aTimeoutAndACrashAnswerWithThePlatformPagesAndTheNextRequestGetsAFreshEnvironment() throws Exception {
        String domain = createFunction(3);

        Response first = given().header("Host", domain).when().get("/status/200");
        first.then().statusCode(200);
        String logStream = first.header("lambda-web-logstream");

        Response timeout = given().header("Host", domain).when().get("/slow");
        timeout.then().statusCode(504)
                .header("lambda-web-error-type", "Runtime.RequestTimeout")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.REQUEST_TIMEOUT,
                        timeout.header("lambda-web-request-id"))));

        // A response that is still streaming at the deadline is cut, as on AWS.
        String streamed = raw("GET /stream HTTP/1.1\r\nHost: " + domain + "\r\nConnection: close\r\n\r\n");
        assertTrue(streamed.startsWith("HTTP/1.1 200 "), streamed);
        assertTrue(streamed.contains("chunk 0"), streamed);
        assertFalse(streamed.contains("done"), streamed);

        Response crash = given().header("Host", domain).when().get("/crash");
        crash.then().statusCode(503)
                .header("lambda-web-error-type", "Runtime.OriginRequestError")
                .body(equalTo(LambdaWebErrorPages.html(LambdaWebErrorPages.ORIGIN_REQUEST_ERROR,
                        crash.header("lambda-web-request-id"))));

        Response after = given().header("Host", domain).when().get("/status/200");
        after.then().statusCode(200).header("lambda-web-logstream", not(equalTo(logStream)));
        assertNotEquals(logStream, after.header("lambda-web-logstream"), "a new environment serves the next request");
    }

    private String createFunction(int timeoutSeconds) throws IOException {
        functionName = "dp-docker-" + Long.toString(System.nanoTime(), 36);
        s3Service.createBucket(BUCKET, "us-east-1");
        s3Service.putObject(BUCKET, functionName + ".zip", zip(APP), "application/zip", Map.of());
        return given().contentType("application/json")
                .body("""
                        {"functionName": "%s",
                         "revisionConfig": {
                           "buildConfig": {"codeConfig": {"s3Object": {"bucket": "%s", "key": "%s.zip"}},
                                           "runtimeConfig": {"runtime": "nodejs24.x"}},
                           "serviceConfig": {"executionRoleArn": "%s", "timeoutSeconds": %d,
                                             "environmentVariables": {"CUSTOM": "yes"}}},
                         "endpointConfig": {"endpointName": "dev", "endpointType": "HomeRegion",
                                            "authType": "ApplicationManaged", "autoDeploymentMode": "LatestRevision"}}
                        """.formatted(functionName, BUCKET, functionName, ROLE, timeoutSeconds))
                .when().put(BASE + "/web-functions")
                .then().statusCode(202)
                .extract().path("endpoint.domainName");
    }

    /** Sends one raw HTTP/1.1 request with {@code Connection: close} and reads until the server closes. */
    private static String raw(String request) throws IOException {
        try (Socket socket = new Socket("localhost", RestAssured.port)) {
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] zip(String indexJs) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(indexJs.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
