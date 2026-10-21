package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ElastiCacheAclProxyIntegrationTest {
    @TempDir
    Path directory;

    private String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile(directory, "docker-", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(90, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command timed out");
        }
        assertEquals(0, process.exitValue(), () -> {
            try {
                return Files.readString(output);
            } catch (Exception e) {
                return "Docker command failed";
            }
        });
        return Files.readString(output).trim();
    }

    private static String command(Socket socket, String... values) throws Exception {
        StringBuilder request = new StringBuilder("*" + values.length + "\r\n");
        for (String value : values) {
            request.append('$').append(value.getBytes(StandardCharsets.UTF_8).length).append("\r\n")
                    .append(value).append("\r\n");
        }
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
    }

    private static Socket connect(int port) throws Exception {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        return socket;
    }

    @Test
    void backendEnforcesCommandsKeysAndChannelsWithoutDefaultUserEscape() throws Exception {
        try {
            Process docker = new ProcessBuilder("docker", "info").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            Assumptions.assumeTrue(docker.waitFor(10, TimeUnit.SECONDS) && docker.exitValue() == 0,
                    "Docker is required for actual Valkey ACL enforcement");
        } catch (IOException e) {
            Assumptions.abort("Docker is required for actual Valkey ACL enforcement");
        }
        String name = "floci-acl-test-" + UUID.randomUUID();
        ElastiCacheAuthProxy proxy = null;
        try {
            docker("run", "--detach", "--name", name, "--publish", "127.0.0.1::6379", "valkey/valkey:8",
                    "valkey-server", "--protected-mode", "no", "--save", "", "--appendonly", "no");
            int backendPort = Integer.parseInt(docker("inspect", "--format",
                    "{{(index (index .NetworkSettings.Ports \"6379/tcp\") 0).HostPort}}", name));
            AtomicReference<String> access = new AtomicReference<>("on ~* &allowed:* -@all +subscribe");
            proxy = new ElastiCacheAuthProxy("acl-test", AuthMode.PASSWORD, "127.0.0.1", backendPort,
                    new ElastiCacheAuthProxy.PasswordValidator() {
                        @Override
                        public boolean validatePassword(String username, String password) {
                            return "subscriber".equals(username) && "synthetic-password".equals(password);
                        }

                        @Override
                        public boolean hasMembers() { return true; }

                        @Override
                        public AuthMode memberAuthMode(String username) {
                            return "subscriber".equals(username) ? AuthMode.PASSWORD : null;
                        }

                        @Override
                        public String accessString(String username) { return access.get(); }
                    }, mock(SigV4Validator.class));
            proxy.start(0);
            int port = proxy.localPort();
            try (Socket subscriber = connect(port)) {
                assertEquals("+OK", command(subscriber, "AUTH", "subscriber", "synthetic-password"));
                assertTrue(command(subscriber, "SET", "key", "value").startsWith("-NOPERM"));
                assertTrue(command(subscriber, "FLUSHALL").startsWith("-NOPERM"));
                assertTrue(command(subscriber, "AUTH", "default", "anything").startsWith("-WRONGPASS"));
                assertTrue(command(subscriber, "HELLO", "2", "AUTH", "default", "anything").startsWith("-WRONGPASS"));
                assertTrue(command(subscriber, "SET", "key", "still-denied").startsWith("-NOPERM"));
                assertTrue(command(subscriber, "SUBSCRIBE", "forbidden:channel").startsWith("-NOPERM"));
                assertEquals("*3", command(subscriber, "SUBSCRIBE", "allowed:channel"));
            }
            // Permission updates are applied on the next successful authentication.
            access.set("on ~allowed:* &* +@all");
            try (Socket writer = connect(port)) {
                assertEquals("+OK", command(writer, "AUTH", "subscriber", "synthetic-password"));
                assertEquals("+OK", command(writer, "SET", "allowed:key", "value"));
                assertTrue(command(writer, "SET", "other:key", "value").startsWith("-NOPERM"));
                assertTrue(command(writer, "ACL", "SETUSER", "default", "on", "nopass", "+@all").startsWith("-NOPERM"));
                assertTrue(command(writer, "CONFIG", "SET", "requirepass", "").startsWith("-NOPERM"));
                assertTrue(command(writer, "MONITOR").startsWith("-NOPERM"));
                assertTrue(command(writer, "SLOWLOG", "GET").startsWith("-NOPERM"));
            }
            access.set("on ~* &* -@all +ping");
            try (Socket hello = connect(port)) {
                assertTrue(command(hello, "HELLO", "2", "AUTH", "subscriber", "synthetic-password").startsWith("*"));
            }
        } finally {
            if (proxy != null) {
                proxy.stop();
            }
            docker("rm", "--force", "--volumes", name);
        }
    }
}
