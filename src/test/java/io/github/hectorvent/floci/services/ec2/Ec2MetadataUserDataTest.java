package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.testing.MutableClock;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Ec2MetadataUserDataTest {

    private static final String TTL_HEADER = "X-aws-ec2-metadata-token-ttl-seconds";
    private static final String TOKEN_HEADER = "X-aws-ec2-metadata-token";
    private static final String CALLER_IP = "127.0.0.1";

    private final MutableClock clock = new MutableClock();
    private final HttpClient client = HttpClient.newHttpClient();
    private Vertx vertx;
    private Instance instance;
    private Ec2MetadataServer server;
    private String endpoint;

    @BeforeEach
    void startServer() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ec2().imdsPort()).thenReturn(port);
        vertx = Vertx.vertx();
        server = new Ec2MetadataServer(vertx, config, null, clock);
        server.start().get(10, TimeUnit.SECONDS);
        endpoint = "http://127.0.0.1:" + port;

        instance = instance("i-0123456789abcdef0");
        server.registerContainer(CALLER_IP, instance.getInstanceId(), instance);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.stop();
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void returnsOriginalBinaryBytes() throws Exception {
        byte[] original = {0, (byte) 0xff, (byte) 0x80, 10, 13};
        instance.setEncodedUserData(Base64.getEncoder().encodeToString(original));
        instance.setUserData("lossy execution text");
        HttpResponse<byte[]> response = getUserData(null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(original, response.body());
    }

    @Test
    void returnsCompressedBytesWhileExecutionUsesDecodedScript() throws Exception {
        String script = "#!/bin/sh\necho snow: 雪\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(script.getBytes(StandardCharsets.UTF_8));
        }
        String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
        instance.setEncodedUserData(encoded);
        instance.setUserData(Ec2UserDataDecoder.decode(encoded));
        assertEquals(script, instance.getUserData());
        String token = putToken("60").body();
        HttpResponse<byte[]> response = getUserData(token);
        assertEquals(200, response.statusCode());
        assertArrayEquals(bytes.toByteArray(), response.body());
    }

    @Test
    void returnsLegacyUtf8AndWhitespace() throws Exception {
        for (String data : new String[]{"雪\n", " \n\t"}) {
            instance.setUserData(data);
            HttpResponse<byte[]> response = getUserData(null);
            assertEquals(200, response.statusCode());
            assertArrayEquals(
                    data.getBytes(StandardCharsets.UTF_8), response.body());
            instance.setEncodedUserData(Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8)));
            HttpResponse<byte[]> encodedResponse = getUserData(null);
            assertEquals(200, encodedResponse.statusCode());
            assertArrayEquals(data.getBytes(StandardCharsets.UTF_8), encodedResponse.body());
            instance.setEncodedUserData(null);
        }
    }

    @Test
    void absentAndEmptyUserDataRemainNotFound() throws Exception {
        assertEquals(404, getUserData(null).statusCode());
        instance.setUserData("");
        assertEquals(404, getUserData(null).statusCode());
        instance.setUserData("stale text");
        for (String encoded : new String[]{"", " \n\t"}) {
            instance.setEncodedUserData(encoded);
            assertEquals(404, getUserData(null).statusCode());
        }
    }

    @Test
    void alreadyStoredExpandedUserDataIsNotSubjectToTheIngestionLimit() throws Exception {
        byte[] data = "A".repeat(32 * 1024).getBytes(StandardCharsets.UTF_8);
        instance.setUserData(new String(data, StandardCharsets.UTF_8));
        instance.setEncodedUserData(Base64.getEncoder().encodeToString(data));
        HttpResponse<byte[]> response = getUserData(null);
        assertEquals(200, response.statusCode());
        assertArrayEquals(data, response.body());
    }

    @Test
    void invalidTokenCannotReadUserData() throws Exception {
        instance.setUserData("private user data");
        assertEquals(401, getUserData("invalid-token").statusCode());
    }

    private HttpResponse<byte[]> getUserData(String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint + "/latest/user-data"));
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Instance instance(String instanceId) {
        Instance instance = new Instance();
        instance.setInstanceId(instanceId);
        return instance;
    }

    private HttpResponse<String> putToken(String ttl) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token"))
                        .header(TTL_HEADER, ttl).PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

}
