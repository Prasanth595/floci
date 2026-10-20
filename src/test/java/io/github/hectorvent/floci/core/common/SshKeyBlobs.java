package io.github.hectorvent.floci.core.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** OpenSSH public key blobs and lines built field by field, for tests of the readers that parse them. */
public final class SshKeyBlobs {

    private SshKeyBlobs() {
    }

    /** An OpenSSH blob of the given type followed by the given fields, each length-prefixed. */
    public static byte[] blob(String type, byte[]... fields) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        int length = Integer.BYTES + typeBytes.length;
        for (byte[] field : fields) {
            length += Integer.BYTES + field.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(length).putInt(typeBytes.length).put(typeBytes);
        for (byte[] field : fields) {
            buffer.putInt(field.length).put(field);
        }
        return buffer.array();
    }

    /** An OpenSSH line, without a comment, whose blob holds the type and then these fields. */
    public static String line(String type, byte[]... fields) {
        return type + " " + Base64.getEncoder().encodeToString(blob(type, fields));
    }

    /** The bytes of {@code first} and then those of {@code second}. */
    public static byte[] concat(byte[] first, byte[] second) {
        return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
    }
}
