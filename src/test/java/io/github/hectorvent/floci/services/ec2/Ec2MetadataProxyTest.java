package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.model.ContainerNetwork;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2MetadataProxyTest {

    /** Lets curl reach the loopback endpoints even when the build host sets a proxy. */
    private static final Map<String, String> LOOPBACK_WITHOUT_PROXY = Map.of(
            "NO_PROXY", "127.0.0.1", "no_proxy", "127.0.0.1");

    @Test
    void installCommandContainsSupportedPackageManagers() {
        String[] command = Ec2MetadataProxy.installCommand();
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("command -v ip >/dev/null 2>&1 && command -v socat >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then exit 0; fi"));
        assertTrue(script.contains("apt-get install -y --no-install-recommends iproute2 socat curl ca-certificates"));
        assertTrue(script.contains("dnf install -y --allowerasing iproute socat curl ca-certificates"));
        assertTrue(script.contains("yum install -y iproute socat curl ca-certificates"));
        assertTrue(script.contains("apk add --no-cache iproute2 socat curl ca-certificates"));
    }

    @Test
    void startCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.startCommand("10.0.0.1", 9169);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        // Attaches address if missing
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.169.254/32' || ip addr add 169.254.169.254/32 dev lo"));
        // Idempotent when pid file exists and process is alive
        assertTrue(script.contains("if [ -f /tmp/floci-imds-proxy.pid ] && kill -0 \"$(cat /tmp/floci-imds-proxy.pid)\" 2>/dev/null; then\n  exit 0\nfi"));
        // Targets configured Floci host and IMDS port
        assertTrue(script.contains("nohup socat TCP-LISTEN:80,bind=169.254.169.254,fork,reuseaddr TCP:10.0.0.1:9169 >/tmp/floci-imds-proxy.log 2>&1 &"));
        // Verifies through an IMDSv2 token even when tokenless access is refused.
        assertTrue(script.contains("-X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'"));
        assertTrue(script.contains("http://169.254.169.254/latest/api/token"));
        assertTrue(script.contains("-H \"X-aws-ec2-metadata-token: $token\" http://169.254.169.254/latest/meta-data/instance-id"));
    }

    @Test
    void podIdentityStartCommandAttachesAddressIdempotentlyAndTargetsHostAndPort() {
        String[] command = Ec2MetadataProxy.podIdentityStartCommand("10.0.0.1", 4566);
        assertEquals(3, command.length);
        assertEquals("sh", command[0]);
        assertEquals("-c", command[1]);

        String script = command[2];
        assertTrue(script.contains("ip addr show dev lo | grep -q '169.254.170.23/32' || ip addr add 169.254.170.23/32 dev lo"));
        assertTrue(script.contains("if [ -f /tmp/floci-pod-identity-proxy.pid ] && kill -0 \"$(cat /tmp/floci-pod-identity-proxy.pid)\" 2>/dev/null; then\n  exit 0\nfi"));
        assertTrue(script.contains("nohup socat TCP-LISTEN:80,bind=169.254.170.23,fork,reuseaddr TCP:10.0.0.1:4566 >/tmp/floci-pod-identity-proxy.log 2>&1 &"));
        assertTrue(script.contains("http://169.254.170.23/v1/credentials"));
    }

    @Test
    void preferredMetadataSourceIpPrefersConfiguredNetworkOverBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");
        ContainerNetwork vpc = new ContainerNetwork();
        vpc.withIpv4Address("10.0.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge, "custom-net", vpc));
        assertTrue(ip.isPresent());
        assertEquals("10.0.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpFallsBackToBridge() {
        ContainerNetwork bridge = new ContainerNetwork();
        bridge.withIpv4Address("172.17.0.2");

        Optional<String> ip = Ec2MetadataProxy.preferredMetadataSourceIp(Map.of("bridge", bridge));
        assertTrue(ip.isPresent());
        assertEquals("172.17.0.2", ip.get());
    }

    @Test
    void preferredMetadataSourceIpReturnsEmptyWhenNoNetworks() {
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(null).isEmpty());
        assertTrue(Ec2MetadataProxy.preferredMetadataSourceIp(Map.of()).isEmpty());
    }

    @Test
    void readinessProbeReadsTheInstanceIdWithATokenWhenTheInstanceRequiresOne(@TempDir Path directory)
            throws Exception {
        try (Ec2MetadataTestFixture fixture = new Ec2MetadataTestFixture()) {
            fixture.start();
            LaunchTemplateData.MetadataOptions options = LaunchTemplateData.MetadataOptions.launchDefaults();
            options.setHttpTokens("required");
            fixture.instance().setMetadataOptions(options);

            assertEquals(0, runShell(new String[]{"sh", "-c", Ec2MetadataProxy.imdsProbeCommand(fixture.endpoint())},
                    directory, LOOPBACK_WITHOUT_PROXY), shellOutput(directory));
        }
    }

    /**
     * Floci's IMDS issues a token for every valid request, so the refused and empty token answers
     * come from a stand-in that records what the probe sent.
     */
    @ParameterizedTest
    @CsvSource({"403, rejected", "200, ''"})
    void readinessProbeNeverFallsBackToATokenlessRead(int tokenStatus, String tokenBody, @TempDir Path directory)
            throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/latest/api/token", exchange -> {
            requests.add(exchange.getRequestMethod() + " token "
                    + exchange.getRequestHeaders().getFirst(Ec2MetadataTestFixture.TTL_HEADER));
            byte[] body = tokenBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(tokenStatus, body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/latest/meta-data/instance-id", exchange -> {
            requests.add(exchange.getRequestMethod() + " metadata "
                    + exchange.getRequestHeaders().getFirst(Ec2MetadataTestFixture.TOKEN_HEADER));
            byte[] body = "i-fixture".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String probe = Ec2MetadataProxy.imdsProbeCommand("http://127.0.0.1:" + server.getAddress().getPort());

            int result = runShell(new String[]{"sh", "-c", probe}, directory, LOOPBACK_WITHOUT_PROXY);

            assertNotEquals(0, result, shellOutput(directory));
            assertEquals(List.of("PUT token 60"), requests, shellOutput(directory));
        } finally {
            server.stop(0);
        }
    }

    /** Runs a generated helper shell with {@code /bin/sh}, its output going to {@link #shellOutput}. */
    private static int runShell(String[] command, Path directory, Map<String, String> environment)
            throws Exception {
        List<String> arguments = new ArrayList<>(List.of(command));
        arguments.set(0, "/bin/sh");
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.environment().putAll(environment);
        builder.redirectErrorStream(true).redirectOutput(directory.resolve("shell-output").toFile());
        Process process = builder.start();
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Generated helper shell exceeded its bound");
            return process.exitValue();
        } finally {
            process.destroyForcibly();
        }
    }

    private static String shellOutput(Path directory) throws IOException {
        return Files.readString(directory.resolve("shell-output"));
    }
}
