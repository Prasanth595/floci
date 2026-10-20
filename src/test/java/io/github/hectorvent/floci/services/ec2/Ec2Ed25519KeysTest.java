package io.github.hectorvent.floci.services.ec2;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2Ed25519KeysTest {

    /**
     * The throwaway ED25519 key of the version 2 key file in PuTTY's test suite (test/cryptsuite.py,
     * testPPKLoadSave): its 32-byte private key, and its public key line.
     */
    static final byte[] PUTTY_TEST_KEY =
            HexFormat.of().parseHex("6bef22997c8f2aa7f17b9f930eafdc59c41ad5c5dca5b0e7900fdff006101a58");
    static final String PUTTY_TEST_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHJCszOHaI9X/yGLtjn22f0hO6VPMQDVtctkym6FJH1W";

    @Test
    void theFingerprintIsSshKeygensSha256WithThePaddingAwsKeeps() {
        // Derived outside this code: "ssh-keygen -l -f" on the public key prints
        //   256 SHA256:Mv99xcHfjQuHbqmIs0Qx5I9kr7lX7Yw97eh4jZtoS70 no comment (ED25519)
        Ec2KeyMaterial.Generated key = Ec2Ed25519Keys.of(new Ed25519PrivateKeyParameters(PUTTY_TEST_KEY, 0));

        assertEquals("Mv99xcHfjQuHbqmIs0Qx5I9kr7lX7Yw97eh4jZtoS70=", key.fingerprint());
        assertEquals(PUTTY_TEST_PUBLIC_KEY, key.openSshPublicKey());
    }

    @Test
    void thePrivateKeyIsAnOpenSshKeyFileOfTheSameKey() throws IOException {
        Ec2KeyMaterial.Generated key = Ec2Ed25519Keys.of(new Ed25519PrivateKeyParameters(PUTTY_TEST_KEY, 0));

        List<String> lines = key.privateKeyPem().lines().toList();
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines.getFirst());
        assertEquals("-----END OPENSSH PRIVATE KEY-----", lines.getLast());
        assertTrue(key.privateKeyPem().endsWith("\n"));
        // ssh-keygen's line length, though OpenSSH reads any
        lines.subList(1, lines.size() - 2).forEach(line -> assertEquals(70, line.length(), line));
        assertArrayEquals(PUTTY_TEST_KEY,
                Ec2CreateKeyPairFixtures.openSshEd25519Key(key.privateKeyPem()).getEncoded());
    }

    @Test
    void everyGeneratedKeyIsNewAndMatchesItsPublicHalf() throws IOException {
        Ec2KeyMaterial.Generated first = Ec2KeyMaterial.generate("ed25519", "pem", "first");
        Ec2KeyMaterial.Generated second = Ec2KeyMaterial.generate("ed25519", "pem", "second");

        assertNotEquals(first.openSshPublicKey(), second.openSshPublicKey());
        assertNotEquals(first.fingerprint(), second.fingerprint());
        for (Ec2KeyMaterial.Generated key : List.of(first, second)) {
            byte[] blob = Ec2CreateKeyPairFixtures.sshEd25519Blob(
                    Ec2CreateKeyPairFixtures.openSshEd25519Key(key.privateKeyPem()));
            assertEquals("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob), key.openSshPublicKey());
        }
    }

    @Test
    void aTypeOrFormatAwsDoesNotOfferIsNotGenerated() {
        // The request is refused before this point; reaching it with another value is a bug, not a default.
        assertThrows(IllegalArgumentException.class, () -> Ec2KeyMaterial.generate("ecdsa", "pem", "name"));
        assertThrows(IllegalArgumentException.class, () -> Ec2KeyMaterial.generate("rsa", "der", "name"));
    }
}
