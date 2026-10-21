package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElastiCacheTlsProxyTest {
    @TempDir
    Path directory;

    private static SSLContext trusted(FlociCertificateAuthority authority) throws Exception {
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null, null);
        trust.setCertificateEntry("floci", authority.certificate());
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trust);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), null);
        return context;
    }

    private ElastiCacheAuthProxy proxy(FlociCertificateAuthority authority, int backendPort) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.hostname()).thenReturn(Optional.of("cache.test"));
        when(config.services().elasticache().clusterAnnounceHostname()).thenReturn(Optional.of("nodes.test"));
        ElastiCacheTlsCertificates certificates = new ElastiCacheTlsCertificates(authority, config);
        return new ElastiCacheAuthProxy("tls-test", AuthMode.PASSWORD, "127.0.0.1", backendPort,
                new ElastiCacheAuthProxy.PasswordValidator() {
                    @Override
                    public boolean validatePassword(String username, String password) {
                        return "synthetic-password".equals(password);
                    }

                    @Override
                    public boolean transitEncryptionEnabled() {
                        return true;
                    }
                }, mock(SigV4Validator.class), certificates::sslContext);
    }

    @Test
    void tlsVerifiesTheSharedCaAndEndpointNameThenAuthenticatesAndRelays() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        try (ServerSocket backend = new ServerSocket(0)) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = backend.accept()) {
                    socket.setSoTimeout(5000);
                    String[] command = new RespReader(socket.getInputStream()).readCommand();
                    socket.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    return command[0];
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            ElastiCacheAuthProxy proxy = proxy(authority, backend.getLocalPort());
            proxy.start(0);
            int port = proxy.localPort();
            try (Socket tcp = new Socket("127.0.0.1", port);
                    SSLSocket client = (SSLSocket) trusted(authority).getSocketFactory()
                            .createSocket(tcp, "cache.test", port, true)) {
                SSLParameters parameters = client.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                client.setSSLParameters(parameters);
                client.setSoTimeout(5000);
                client.startHandshake();
                client.getOutputStream().write("*2\r\n$4\r\nAUTH\r\n$18\r\nsynthetic-password\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                assertEquals("+OK", reader.readLine());
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                assertEquals("+PONG", reader.readLine());
                assertEquals("PING", received.get(5, TimeUnit.SECONDS));
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void encryptedEndpointRejectsPlaintextBeforeBackendAccess() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        try (ServerSocket backend = new ServerSocket(0)) {
            backend.setSoTimeout(500);
            ElastiCacheAuthProxy proxy = proxy(authority, backend.getLocalPort());
            proxy.start(0);
            int port = proxy.localPort();
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(5000);
                client.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                // A fatal TLS alert or EOF is acceptable; no RESP success can be returned.
                int first = client.getInputStream().read();
                assertTrue(first == -1 || first == 21);
                assertThrows(SocketTimeoutException.class, backend::accept);
            } finally {
                proxy.stop();
            }
        }
    }
    @Test
    void rejectsAnUntrustedCaAndAnIncorrectEndpointName() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory.resolve("server"));
        FlociCertificateAuthority wrong = FlociCertificateAuthority.loadOrCreate(directory.resolve("untrusted"));
        try (ServerSocket backend = new ServerSocket(0)) {
            backend.setSoTimeout(500);
            ElastiCacheAuthProxy proxy = proxy(authority, backend.getLocalPort());
            proxy.start(0);
            int port = proxy.localPort();
            try {
                for (boolean wrongName : new boolean[]{false, true}) {
                    SSLContext context = trusted(wrongName ? authority : wrong);
                    try (Socket tcp = new Socket("127.0.0.1", port);
                            SSLSocket client = (SSLSocket) context.getSocketFactory().createSocket(tcp,
                                    wrongName ? "wrong.test" : "cache.test", port, true)) {
                        SSLParameters parameters = client.getSSLParameters();
                        parameters.setEndpointIdentificationAlgorithm("HTTPS");
                        client.setSSLParameters(parameters);
                        client.setSoTimeout(5000);
                        assertThrows(IOException.class, client::startHandshake);
                    }
                }
                assertThrows(SocketTimeoutException.class, backend::accept);
            } finally {
                proxy.stop();
            }
        }
    }

}
