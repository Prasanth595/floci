package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.Digests;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The ED25519 key pair CreateKeyPair creates for {@code KeyType=ed25519}.
 *
 * <p>Its private key is returned in OpenSSH's own private key format, the
 * {@code BEGIN OPENSSH PRIVATE KEY} block, as AWS returns an ED25519 key: PKCS#1, the RSA form, has
 * no ED25519 encoding. Its fingerprint is the base64-encoded SHA-256 digest of the public key blob,
 * the value {@code ssh-keygen -l} prints after {@code SHA256:}, with the {@code =} padding AWS keeps
 * and ssh-keygen drops.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/verify-keys.html">Verify the fingerprint of your key pair</a>
 */
final class Ec2Ed25519Keys {

    /** The base64 line length of ssh-keygen's private key files; readers take any. */
    private static final int OPENSSH_LINE_LENGTH = 70;

    private Ec2Ed25519Keys() {}

    static Ec2KeyMaterial.Generated generate() {
        return of(new Ed25519PrivateKeyParameters(new SecureRandom()));
    }

    /** The material CreateKeyPair returns for a given ED25519 private key. */
    static Ec2KeyMaterial.Generated of(Ed25519PrivateKeyParameters privateKey) {
        byte[] blob;
        byte[] encoded;
        try {
            blob = OpenSSHPublicKeyUtil.encodePublicKey(privateKey.generatePublicKey());
            encoded = OpenSSHPrivateKeyUtil.encodePrivateKey(privateKey);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode an ED25519 key pair", e);
        }
        Base64.Encoder base64 = Base64.getEncoder();
        return new Ec2KeyMaterial.Generated(armour(base64.encodeToString(encoded)),
                "ssh-ed25519 " + base64.encodeToString(blob), base64.encodeToString(Digests.sha256(blob)));
    }

    private static String armour(String body) {
        StringBuilder pem = new StringBuilder("-----BEGIN OPENSSH PRIVATE KEY-----\n");
        for (int i = 0; i < body.length(); i += OPENSSH_LINE_LENGTH) {
            pem.append(body, i, Math.min(i + OPENSSH_LINE_LENGTH, body.length())).append('\n');
        }
        return pem.append("-----END OPENSSH PRIVATE KEY-----\n").toString();
    }
}
