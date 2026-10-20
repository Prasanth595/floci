package io.github.hectorvent.floci.services.ec2;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PuTTY private key file, version 2, read the way PuTTY reads one: written for the tests and
 * independent of the code that writes these files, so a test checks the format rather than the
 * writer's own idea of it. The layout and the MAC follow PuTTY's sshpubk.c: a version line, an
 * encryption line and a comment, the public blob and the private blob as counted lines of base64,
 * and an HMAC-SHA1, keyed with the SHA-1 of "putty-private-key-file-mac-key" when unencrypted, over
 * the algorithm, encryption, comment and both blobs, each as an SSH string.
 */
record PuttyKeyFile(String algorithm, String encryption, String comment, byte[] publicBlob, byte[] privateBlob,
                    String mac) {

    static PuttyKeyFile parse(String text) {
        assertTrue(text.startsWith("PuTTY-User-Key-File-2: "),
                "not a PuTTY version 2 key file: " + text.lines().findFirst().orElse(""));
        assertTrue(text.endsWith("\n"), "a PuTTY key file ends its last line");
        List<String> lines = text.lines().toList();
        Map<String, String> headers = new LinkedHashMap<>();
        byte[] publicBlob = null;
        byte[] privateBlob = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int colon = line.indexOf(": ");
            assertTrue(colon > 0, "line " + (i + 1) + " is not a header line");
            String name = line.substring(0, colon);
            String value = line.substring(colon + 2);
            headers.put(name, value);
            if (name.equals("Public-Lines") || name.equals("Private-Lines")) {
                int count = Integer.parseInt(value);
                List<String> body = lines.subList(i + 1, i + 1 + count);
                for (int j = 0; j < body.size() - 1; j++) {
                    assertEquals(64, body.get(j).length(), "PuTTY writes 64 base64 characters a line");
                }
                byte[] blob = Base64.getDecoder().decode(String.join("", body));
                if (name.equals("Public-Lines")) {
                    publicBlob = blob;
                } else {
                    privateBlob = blob;
                }
                i += count;
            }
        }
        assertEquals(List.of("PuTTY-User-Key-File-2", "Encryption", "Comment", "Public-Lines", "Private-Lines",
                "Private-MAC"), new ArrayList<>(headers.keySet()));
        return new PuttyKeyFile(headers.get("PuTTY-User-Key-File-2"), headers.get("Encryption"),
                headers.get("Comment"), publicBlob, privateBlob, headers.get("Private-MAC"));
    }

    boolean macIsValid() throws GeneralSecurityException {
        byte[] key = MessageDigest.getInstance("SHA-1")
                .digest("putty-private-key-file-mac-key".getBytes(StandardCharsets.US_ASCII));
        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] expected = hmac.doFinal(sshStrings(algorithm.getBytes(StandardCharsets.UTF_8),
                encryption.getBytes(StandardCharsets.UTF_8), comment.getBytes(StandardCharsets.UTF_8),
                publicBlob, privateBlob));
        return HexFormat.of().formatHex(expected).equals(mac);
    }

    /** The fields of an SSH blob: each a four-byte length and that many bytes. */
    static List<byte[]> fields(byte[] blob) {
        ByteBuffer buffer = ByteBuffer.wrap(blob);
        List<byte[]> fields = new ArrayList<>();
        while (buffer.hasRemaining()) {
            byte[] field = new byte[buffer.getInt()];
            buffer.get(field);
            fields.add(field);
        }
        return fields;
    }

    /**
     * The RSA key the file holds, rebuilt from its public exponent and modulus and its private exponent,
     * primes and coefficient, after checking they make one consistent key.
     */
    RSAPrivateCrtKey rsaKey() throws GeneralSecurityException {
        List<byte[]> publicFields = fields(publicBlob);
        assertEquals("ssh-rsa", new String(publicFields.get(0), StandardCharsets.UTF_8));
        BigInteger e = new BigInteger(publicFields.get(1));
        BigInteger n = new BigInteger(publicFields.get(2));
        List<byte[]> privateFields = fields(privateBlob);
        assertEquals(4, privateFields.size(), "ssh-rsa private fields: d, p, q, iqmp");
        BigInteger d = new BigInteger(privateFields.get(0));
        BigInteger p = new BigInteger(privateFields.get(1));
        BigInteger q = new BigInteger(privateFields.get(2));
        BigInteger iqmp = new BigInteger(privateFields.get(3));
        assertEquals(n, p.multiply(q));
        assertTrue(p.compareTo(q) > 0, "PuTTY keeps the larger prime first");
        assertEquals(BigInteger.ONE, iqmp.multiply(q).mod(p), "iqmp is the inverse of q modulo p");
        assertEquals(BigInteger.ONE, e.multiply(d).mod(p.subtract(BigInteger.ONE)));
        assertEquals(BigInteger.ONE, e.multiply(d).mod(q.subtract(BigInteger.ONE)));
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(n, e, d, p, q,
                d.mod(p.subtract(BigInteger.ONE)), d.mod(q.subtract(BigInteger.ONE)), iqmp));
    }

    /** The ED25519 key the file holds, after checking its public blob is that key's public half. */
    Ed25519PrivateKeyParameters ed25519Key() throws IOException {
        List<byte[]> privateFields = fields(privateBlob);
        assertEquals(1, privateFields.size(), "ssh-ed25519 private fields: the 32-byte private key");
        Ed25519PrivateKeyParameters key = new Ed25519PrivateKeyParameters(privateFields.get(0), 0);
        assertTrue(Arrays.equals(publicBlob, OpenSSHPublicKeyUtil.encodePublicKey(key.generatePublicKey())),
                "the public blob is the public half of the private key");
        return key;
    }

    static byte[] sshStrings(byte[]... fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] field : fields) {
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(field.length).array());
            out.writeBytes(field);
        }
        return out.toByteArray();
    }
}
