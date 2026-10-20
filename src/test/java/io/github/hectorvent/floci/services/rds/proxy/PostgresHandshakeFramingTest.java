package io.github.hectorvent.floci.services.rds.proxy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PostgresHandshakeFramingTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void clientLoginResponsesAreBufferedUntilTheCompleteResponseIsReady(boolean rejected) throws Exception {
        RecordingOutputStream output = new RecordingOutputStream();
        Socket client = new MemorySocket(startupAndPassword(), output);
        Socket backend = new MemorySocket(new byte[] {
                'R', 0, 0, 0, 8, 0, 0, 0, 0,
                'Z', 0, 0, 0, 5, 'I'
        }, new RecordingOutputStream());
        AtomicInteger backendConnections = new AtomicInteger();
        try (client; backend) {
            PostgresProtocolHandler.AuthenticatedSession session = PostgresProtocolHandler.authenticate(
                    client, () -> {
                        backendConnections.incrementAndGet();
                        return backend;
                    }, "master", "password", "appdb", false, null, null, null,
                    (user, password) -> rejected ? PasswordValidator.AuthResult.REJECT
                            : PasswordValidator.AuthResult.MASTER_EQUIVALENT, 5000);

            assertArrayEquals(new byte[] {'R', 0, 0, 0, 8, 0, 0, 0, 3}, output.writes.getFirst());
            assertEquals(2, output.writes.size());
            assertEquals(2, output.flushes);
            if (rejected) {
                assertNull(session);
                assertEquals(0, backendConnections.get());
                byte[] error = output.writes.get(1);
                assertEquals('E', error[0]);
                assertEquals(error.length - 1, ByteBuffer.wrap(error, 1, 4).getInt());
                assertArrayEquals(("SFATAL\0C28P01\0Mpassword authentication failed for user \"master\"\0\0")
                        .getBytes(StandardCharsets.UTF_8), Arrays.copyOfRange(error, 5, error.length));
            } else {
                assertNotNull(session);
                assertEquals(1, backendConnections.get());
                assertArrayEquals(new byte[] {
                        'R', 0, 0, 0, 8, 0, 0, 0, 0,
                        'Z', 0, 0, 0, 5, 'I'
                }, output.writes.get(1));
            }
        }
    }

    private static byte[] startupAndPassword() throws IOException {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(input);
        byte[] parameters = "user\0master\0database\0appdb\0\0".getBytes(StandardCharsets.UTF_8);
        out.writeInt(8 + parameters.length);
        out.writeInt(196608);
        out.write(parameters);
        byte[] password = "password\0".getBytes(StandardCharsets.UTF_8);
        out.writeByte('p');
        out.writeInt(4 + password.length);
        out.write(password);
        return input.toByteArray();
    }

    private static final class RecordingOutputStream extends ByteArrayOutputStream {
        private final List<byte[]> writes = new ArrayList<>();
        private int flushes;

        @Override
        public void write(int value) {
            writes.add(new byte[] {(byte) value});
            super.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            writes.add(Arrays.copyOfRange(bytes, offset, offset + length));
            super.write(bytes, offset, length);
        }

        @Override
        public void flush() {
            flushes++;
        }
    }

    private static final class MemorySocket extends Socket {
        private final InputStream input;
        private final RecordingOutputStream output;

        private MemorySocket(byte[] input, RecordingOutputStream output) {
            this.input = new ByteArrayInputStream(input);
            this.output = output;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public RecordingOutputStream getOutputStream() {
            return output;
        }
    }
}
