package io.github.hectorvent.floci.services.ec2;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CreateKeyPair previously returned a fixed 63-character string literal as KeyMaterial. It
 * looked like a PEM but did not parse ("ssh-keygen -y -f key.pem: invalid format"), so no
 * caller could ever use it -- and it was byte-identical for every key pair.
 *
 * <p>These tests deliberately parse and use the material rather than matching it against a
 * pattern: a placeholder passes any shape check, which is how the original went unnoticed.
 */
class Ec2KeyMaterialTest {

    /** Throwaway keys, kept fixed so their fingerprints can be pinned to known good values. */
    private static final String RSA_PUBLIC_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCp7mGC9"
            + "NkQI+loxf1G9bM6HnCs9iR1nnzZA/f/o7hx/Wv1oDhx03k6H83I+Q49eE1XO56WBPxnr8/2G6UmS9D0R"
            + "FKe9L+HJrfiZF7oLQ09JwEK91VLNSkD0Bq2zhnfWJe/ULkaPQ7FgHEghRi8aI5PsATH6VCaJDKWxl+2b"
            + "zM7MWlbKRAo8uuu2evnGrgnu+RmuXJQCRYz6lG+JESVzm6MnHXYxme+UD+7c/tTYwzoswfXh8VN8QVzX"
            + "mjfHi2Ve3PJ+YuF2X2gKpRMNMf7cLWMCTOhZI2AgXX+NLDlCG0dEUm/DXdSKRTDhm3mIJmF67eGYuff+"
            + "zHusBZ9cSBkW9i9";

    private static final String ED25519_PUBLIC_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj";

    /** RSA_PUBLIC_KEY's fingerprint: {@code openssl pkey -pubin -outform DER | openssl md5 -c}. */
    private static final String RSA_FINGERPRINT = "4d:1a:39:2e:6a:18:60:9a:c5:2a:cb:cc:6c:de:22:b5";

    /** RSA_PUBLIC_KEY as RFC 4716, with a header continued onto a second line by a backslash. */
    private static final String RSA_RFC_4716 = """
            ---- BEGIN SSH2 PUBLIC KEY ----
            Subject: throwaway
            Comment: "a comment that a trailing backslash \\
            continues onto the next line"
            AAAAB3NzaC1yc2EAAAADAQABAAABAQCp7mGC9NkQI+loxf1G9bM6HnCs9iR1nnzZA/f/o7
            hx/Wv1oDhx03k6H83I+Q49eE1XO56WBPxnr8/2G6UmS9D0RFKe9L+HJrfiZF7oLQ09JwEK
            91VLNSkD0Bq2zhnfWJe/ULkaPQ7FgHEghRi8aI5PsATH6VCaJDKWxl+2bzM7MWlbKRAo8u
            uu2evnGrgnu+RmuXJQCRYz6lG+JESVzm6MnHXYxme+UD+7c/tTYwzoswfXh8VN8QVzXmjf
            Hi2Ve3PJ+YuF2X2gKpRMNMf7cLWMCTOhZI2AgXX+NLDlCG0dEUm/DXdSKRTDhm3mIJmF67
            eGYuff+zHusBZ9cSBkW9i9
            ---- END SSH2 PUBLIC KEY ----
            """;

    /** RSA_PUBLIC_KEY as a PEM SubjectPublicKeyInfo. */
    private static final String RSA_PEM_SPKI = """
            -----BEGIN PUBLIC KEY-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqe5hgvTZECPpaMX9RvWz
            Oh5wrPYkdZ582QP3/6O4cf1r9aA4cdN5Oh/NyPkOPXhNVzuelgT8Z6/P9hulJkvQ
            9ERSnvS/hya34mRe6C0NPScBCvdVSzUpA9Aats4Z31iXv1C5Gj0OxYBxIIUYvGiO
            T7AEx+lQmiQylsZftm8zOzFpWykQKPLrrtnr5xq4J7vkZrlyUAkWM+pRviRElc5u
            jJx12MZnvlA/u3P7U2MM6LMH14fFTfEFc15o3x4tlXtzyfmLhdl9oCqUTDTH+3C1
            jAkzoWSNgIF1/jSw5QhtHRFJvw13UikUw4Zt5iCZheu3hmLn3/sx7rAWfXEgZFvY
            vQIDAQAB
            -----END PUBLIC KEY-----
            """;

    /** RSA_PUBLIC_KEY as the bare PKCS#1 PEM that {@code ssh-keygen -e -m PEM} writes. */
    private static final String RSA_PEM_PKCS1 = """
            -----BEGIN RSA PUBLIC KEY-----
            MIIBCgKCAQEAqe5hgvTZECPpaMX9RvWzOh5wrPYkdZ582QP3/6O4cf1r9aA4cdN5
            Oh/NyPkOPXhNVzuelgT8Z6/P9hulJkvQ9ERSnvS/hya34mRe6C0NPScBCvdVSzUp
            A9Aats4Z31iXv1C5Gj0OxYBxIIUYvGiOT7AEx+lQmiQylsZftm8zOzFpWykQKPLr
            rtnr5xq4J7vkZrlyUAkWM+pRviRElc5ujJx12MZnvlA/u3P7U2MM6LMH14fFTfEF
            c15o3x4tlXtzyfmLhdl9oCqUTDTH+3C1jAkzoWSNgIF1/jSw5QhtHRFJvw13UikU
            w4Zt5iCZheu3hmLn3/sx7rAWfXEgZFvYvQIDAQAB
            -----END RSA PUBLIC KEY-----
            """;

    /** RSA_PUBLIC_KEY as base64 DER, a SubjectPublicKeyInfo. */
    private static final String RSA_DER_SPKI = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAqe5hgvTZECPpaMX9RvWzOh5"
            + "wrPYkdZ582QP3/6O4cf1r9aA4cdN5Oh/NyPkOPXhNVzuelgT8Z6/P9hulJkvQ9ERSnvS/hya34mRe6C0NPScBCvdVSzUpA9Aats"
            + "4Z31iXv1C5Gj0OxYBxIIUYvGiOT7AEx+lQmiQylsZftm8zOzFpWykQKPLrrtnr5xq4J7vkZrlyUAkWM+pRviRElc5ujJx12MZnvl"
            + "A/u3P7U2MM6LMH14fFTfEFc15o3x4tlXtzyfmLhdl9oCqUTDTH+3C1jAkzoWSNgIF1/jSw5QhtHRFJvw13UikUw4Zt5iCZheu3h"
            + "mLn3/sx7rAWfXEgZFvYvQIDAQAB";

    /** RSA_PUBLIC_KEY as base64 DER, the bare PKCS#1 RSAPublicKey. */
    private static final String RSA_DER_PKCS1 = "MIIBCgKCAQEAqe5hgvTZECPpaMX9RvWzOh5wrPYkdZ582QP3/6O4cf1r9aA4cdN5"
            + "Oh/NyPkOPXhNVzuelgT8Z6/P9hulJkvQ9ERSnvS/hya34mRe6C0NPScBCvdVSzUpA9Aats4Z31iXv1C5Gj0OxYBxIIUYvGiOT7AE"
            + "x+lQmiQylsZftm8zOzFpWykQKPLrrtnr5xq4J7vkZrlyUAkWM+pRviRElc5ujJx12MZnvlA/u3P7U2MM6LMH14fFTfEFc15o3x4t"
            + "lXtzyfmLhdl9oCqUTDTH+3C1jAkzoWSNgIF1/jSw5QhtHRFJvw13UikUw4Zt5iCZheu3hmLn3/sx7rAWfXEgZFvYvQIDAQAB";

    /** A throwaway 1024-bit RSA key from {@code ssh-keygen -t rsa -b 1024}. */
    private static final String RSA_1024_PUBLIC_KEY = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQDDMEMPnK6zq4YbIZrEVotEGUJIAS"
            + "cQzFZSvmFegy0PHGRK6QNJWIK0eh+dNZ+hckR/X/LQvliDhCndX++/r/n3eZxmD3OXam4yg8RHT5k1Yj9eZCPxC8w1gfdDicoFIpa8"
            + "BZLVUf0/7u3KH6rpgJyW0sH2Fg7nI4WcOfEJRc+qUw== throwaway";

    /** A throwaway DSA key from {@code ssh-keygen -t dsa}. */
    private static final String DSA_PUBLIC_KEY = "ssh-dss AAAAB3NzaC1kc3MAAACBAOFToDsRbL7Z8AqAZOKXROc1Cy5SA1ZgM5odRC57"
            + "MSEruUjfUv2//jGbOLemgQBFsqZY1dyKlrpR3dm+tpfXGQR26Pkqm+5BF8kaykQZAYdwCzRhwzM7c8pei36Qi2XJ1s+MeLbIXZ2TTf"
            + "yvkYhckXSrnf90oVJaMpaTIiDzu/CVAAAAFQCk1LBHtzCpTFHCInI4jnrGMjyyQQAAAIBz+pU2CQ0d8tQyxfQJ71wCCowSoVuUk5Zh"
            + "1kAnIOoSPOTOuqos4OqicYdSH/AK8G0gKoXbp1BJX8H1yB401LAl8gccL1YbZBheZAtfNjeBd1xOZQZXWSYOEPQWVMcrqkId3un13z"
            + "55qV0vl6ohU3Mp9ekGjBHuQWhDk8RYGgL9vwAAAIB6eL+huYUrgr06l0ozSlF9pf87rehfJaCLLJPPco8F9uCqx8sYsi/8b6gkbfq4"
            + "w/k75H62ew8LVhpab9bP3/6/HQodnGrh1rthcapynsUzpFcjS86okexUcDkR3OnPLJ3CNGBS7MM0yw+NeXZN2BkeTdKAfbAdFw1mrb"
            + "/AKu9GDg== throwaway";

    /** Throwaway ECDSA keys from {@code ssh-keygen -t ecdsa -b 256} and {@code -b 384}. */
    private static final String ECDSA_P256_PUBLIC_KEY = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAy"
            + "NTYAAABBBFaxjAHsoCE0FmjbKLGzgVRKfOKFmYj1/JpnSjsuLjy23mHIUPkLJ5thgE47igHp+KufC1V2KqJE4OP5w4EK9SU= throwaway";
    private static final String ECDSA_P384_PUBLIC_KEY = "ecdsa-sha2-nistp384 AAAAE2VjZHNhLXNoYTItbmlzdHAzODQAAAAIbmlzdHAz"
            + "ODQAAABhBEFD+laPV5eyv8Xz7vR3C2ghLq0L8tSwATYS5Nalon9hyyVbf9+wSWLKJH7rmq374ljT0Uh0vuy47nzXJT9pEVtSthFnpue9"
            + "nxG0TEvsjbQgmcNNCt8vYZPrYWBLc1x7zg== throwaway";

    @Test
    void generatedPrivateKeyParsesAsA2048BitRsaKey() throws Exception {
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();

        Object parsed = new PEMParser(new StringReader(generated.privateKeyPem())).readObject();
        assertTrue(parsed instanceof PEMKeyPair, "expected a PEM key pair, got: " + parsed);
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) new JcaPEMKeyConverter()
                .getKeyPair((PEMKeyPair) parsed).getPrivate();

        assertEquals(2048, privateKey.getModulus().bitLength());
    }

    @Test
    void generatedPrivateKeyUsesThePkcs1HeaderAwsReturns() {
        // AWS returns the traditional "BEGIN RSA PRIVATE KEY" block. Java's own encoding is
        // PKCS#8 ("BEGIN PRIVATE KEY"); OpenSSH reads both, but tooling that matches on the
        // header only reads the former.
        String pem = Ec2KeyMaterial.generateRsa().privateKeyPem();
        assertTrue(pem.startsWith("-----BEGIN RSA PRIVATE KEY-----"), pem.lines().findFirst().orElse(""));
        assertTrue(pem.contains("-----END RSA PRIVATE KEY-----"));
    }

    @Test
    void publicKeyMatchesThePrivateKeyItWasGeneratedWith() throws Exception {
        // The whole point of the pair: what gets written to authorized_keys has to be the
        // other half of what the caller receives, or SSH still fails.
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();

        PEMKeyPair parsed = (PEMKeyPair) new PEMParser(
                new StringReader(generated.privateKeyPem())).readObject();
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) new JcaPEMKeyConverter()
                .getKeyPair(parsed).getPrivate();

        String[] fields = generated.openSshPublicKey().split(" ");
        assertEquals("ssh-rsa", fields[0]);
        ByteBuffer blob = ByteBuffer.wrap(Base64.getDecoder().decode(fields[1]));

        assertEquals("ssh-rsa", new String(read(blob), StandardCharsets.UTF_8));
        assertEquals(privateKey.getPublicExponent(), new BigInteger(read(blob)));
        assertEquals(privateKey.getModulus(), new BigInteger(read(blob)));
    }

    @Test
    void everyKeyPairIsDistinct() {
        // The literal made all key pairs identical, so a second key silently authenticated
        // as the first.
        Ec2KeyMaterial.Generated first = Ec2KeyMaterial.generateRsa();
        Ec2KeyMaterial.Generated second = Ec2KeyMaterial.generateRsa();

        assertNotEquals(first.privateKeyPem(), second.privateKeyPem());
        assertNotEquals(first.openSshPublicKey(), second.openSshPublicKey());
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void fingerprintIsTheColonSeparatedSha1OfTheDerPrivateKey() {
        // "The SHA-1 digest of the DER encoded private key" -- 20 bytes, so 20 hex pairs.
        String fingerprint = Ec2KeyMaterial.generateRsa().fingerprint();

        assertTrue(fingerprint.matches("([0-9a-f]{2}:){19}[0-9a-f]{2}"), fingerprint);
    }

    @Test
    void importedKeyFingerprintIsPerKeyRatherThanAConstant() {
        String first = fingerprintOf(Ec2KeyMaterial.generateRsa().openSshPublicKey());
        String second = fingerprintOf(Ec2KeyMaterial.generateRsa().openSshPublicKey());

        assertNotNull(first);
        // MD5 of the DER public key: 16 bytes.
        assertTrue(first.matches("([0-9a-f]{2}:){15}[0-9a-f]{2}"), first);
        assertNotEquals(first, second);
    }

    @Test
    void importedKeyFingerprintIsStableAndIgnoresTheTrailingComment() {
        // ssh-keygen writes "ssh-rsa AAAA... user@host"; the comment is not part of the key,
        // so re-importing the same key under a different comment must not change its identity.
        String publicKey = Ec2KeyMaterial.generateRsa().openSshPublicKey();

        assertEquals(fingerprintOf(publicKey),
                fingerprintOf(publicKey + " someone@example.com"));
    }

    @Test
    void unparseableImportedMaterialYieldsNoFingerprintRatherThanADigestOfGarbage() {
        assertEquals(null, fingerprintOf("not-a-key"));
        assertEquals(null, fingerprintOf(""));
        assertEquals(null, fingerprintOf(null));
    }

    @Test
    void openSshEncodingRoundTripsAnArbitraryRsaPublicKey() throws Exception {
        // Guards the length-prefixed framing: a wrong length here produces a line that looks
        // right and that sshd silently ignores.
        Ec2KeyMaterial.Generated generated = Ec2KeyMaterial.generateRsa();
        PEMKeyPair parsed = (PEMKeyPair) new PEMParser(
                new StringReader(generated.privateKeyPem())).readObject();
        RSAPublicKey publicKey = (RSAPublicKey) new JcaPEMKeyConverter()
                .getKeyPair(parsed).getPublic();

        assertEquals(generated.openSshPublicKey(), Ec2KeyMaterial.openSshPublicKey(publicKey));
    }

    @Test
    void importedRsaFingerprintMatchesTheSchemeAwsDocuments() {
        // Pinned against a value derived outside this code, from a throwaway key:
        //   openssl rsa -in rsa.pem -pubout -outform DER | openssl md5 -c
        // which is the command AWS gives for verifying an imported RSA key pair. A test that
        // compared the service against fingerprintOf, or that only checked the digest shape,
        // would pass under either scheme and so could not tell them apart.
        String expected = "4d:1a:39:2e:6a:18:60:9a:c5:2a:cb:cc:6c:de:22:b5";

        assertEquals(expected, fingerprintOf(RSA_PUBLIC_KEY));
        assertEquals(expected, fingerprintOf(RSA_PUBLIC_KEY + " someone@example.com"));

        // The other candidate scheme for the same key: MD5 over the SSH wire blob, which is
        // what "ssh-keygen -l -E md5" reports. AWS does not use it for imported RSA keys, and
        // it is a different value, which is what makes the assertion above load bearing.
        assertNotEquals("e7:d6:55:60:68:18:8f:b7:4f:2a:72:20:0b:2f:f2:d9",
                fingerprintOf(RSA_PUBLIC_KEY));
    }

    @Test
    void importedEd25519FingerprintIsTheBase64Sha256AwsReports() {
        // EC2 fingerprints ed25519 keys with SHA-256, not MD5, whichever way the key arrived.
        // Derived independently with "ssh-keygen -l -f ed25519.pub", which prints
        //   SHA256:UOyzahv0Ty520U89wfCvKdTlp2TbtpmnlpJHPW3MbMk
        // AWS reports the same digest without the prefix and with base64 padding kept.
        String fingerprint = fingerprintOf(ED25519_PUBLIC_KEY);

        assertEquals("UOyzahv0Ty520U89wfCvKdTlp2TbtpmnlpJHPW3MbMk=", fingerprint);
        assertEquals(fingerprint,
                fingerprintOf(ED25519_PUBLIC_KEY + " someone@example.com"));
    }

    @Test
    void ed25519MaterialThatIsNotA32ByteKeyYieldsNoFingerprint() {
        assertEquals(null, fingerprintOf(
                "ssh-ed25519 " + Base64.getEncoder().encodeToString(blobOf("ssh-ed25519", new byte[31]))));
    }

    @Test
    void aFieldLongerThanTheBlobIsRejectedRatherThanAllocated() {
        // ImportKeyPair passes the caller's material to this parser, so the declared field
        // length is attacker controlled. A blob that announces a two-gigabyte field is a few
        // bytes on the wire; allocating first turns that into an OutOfMemoryError, which is an
        // Error and so escapes the catch inside readImportedKey and takes the request thread
        // with it. The length has to be rejected before the array exists.
        ByteBuffer overstated = ByteBuffer.allocate(15);
        byte[] type = "ssh-rsa".getBytes(StandardCharsets.UTF_8);
        overstated.putInt(type.length).put(type).putInt(Integer.MAX_VALUE);

        assertEquals(null, fingerprintOf(
                "ssh-rsa " + Base64.getEncoder().encodeToString(overstated.array())));
    }

    @Test
    void negativeAndTruncatedFieldLengthsYieldNoFingerprint() {
        ByteBuffer negative = ByteBuffer.allocate(4).putInt(-1);
        assertEquals(null, fingerprintOf(
                "ssh-rsa " + Base64.getEncoder().encodeToString(negative.array())));

        // Fewer than four bytes left: not even a length prefix.
        assertEquals(null, fingerprintOf(
                "ssh-rsa " + Base64.getEncoder().encodeToString(new byte[]{0, 0, 1})));
    }

    /**
     * The same RSA key in each format AWS documents for ImportKeyPair, all read as one key with the
     * fingerprint pinned above. The exports were made outside this code from RSA_PUBLIC_KEY:
     * {@code ssh-keygen -e -m RFC4716}, {@code -m PKCS8} and {@code -m PEM}, and
     * {@code openssl pkey -pubin -outform DER | base64} and {@code openssl rsa -pubin
     * -RSAPublicKey_out -outform DER | base64} for the bare DER.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "openssh", "openssh-comment", "openssh-tab-crlf", "rfc4716", "pem-spki", "pem-pkcs1",
            "der-spki", "der-spki-wrapped", "der-pkcs1"})
    void anRsaKeyIsReadFromEveryFormatAwsDocumentsAsTheSameKey(String format) {
        String material = switch (format) {
            case "openssh" -> RSA_PUBLIC_KEY;
            case "openssh-comment" -> RSA_PUBLIC_KEY + " someone@example.com\n";
            case "openssh-tab-crlf" -> RSA_PUBLIC_KEY.replace(' ', '\t') + "\tsomeone@example.com\r\n";
            case "rfc4716" -> RSA_RFC_4716;
            case "pem-spki" -> RSA_PEM_SPKI;
            case "pem-pkcs1" -> RSA_PEM_PKCS1;
            case "der-spki" -> RSA_DER_SPKI;
            case "der-spki-wrapped" -> String.join("\n", RSA_DER_SPKI.split("(?<=\\G.{64})")) + "\n";
            case "der-pkcs1" -> RSA_DER_PKCS1;
            default -> throw new IllegalArgumentException(format);
        };

        Ec2KeyMaterial.Imported key = Ec2KeyMaterial.readImportedKey(material);

        assertNotNull(key, format);
        assertEquals("rsa", key.keyType());
        assertEquals(RSA_FINGERPRINT, key.fingerprint());
        // One OpenSSH line without a comment, whatever came in: what authorized_keys can read.
        assertEquals(RSA_PUBLIC_KEY, key.openSshPublicKey());
    }

    @Test
    void anEd25519KeyIsReadFromAnOpenSshLine() {
        Ec2KeyMaterial.Imported key = Ec2KeyMaterial.readImportedKey(ED25519_PUBLIC_KEY + " someone@example.com\n");

        assertNotNull(key);
        assertEquals("ed25519", key.keyType());
        assertEquals("UOyzahv0Ty520U89wfCvKdTlp2TbtpmnlpJHPW3MbMk=", key.fingerprint());
        assertEquals(ED25519_PUBLIC_KEY, key.openSshPublicKey());
    }

    /**
     * EC2 imports RSA and ED25519 keys only. DSA it names as refused; ECDSA, the security-key types
     * and certificates are not among the types it supports, and AWS answers an ECDSA import with
     * InvalidKey.Format. The DSA and ECDSA lines are throwaway keys from ssh-keygen.
     */
    @ParameterizedTest
    @ValueSource(strings = {"dsa", "ecdsa-p256", "ecdsa-p384", "sk-ed25519", "sk-ecdsa", "ed25519-cert", "unknown"})
    void aKeyOfATypeEc2DoesNotImportIsRefused(String type) {
        String material = switch (type) {
            case "dsa" -> DSA_PUBLIC_KEY;
            case "ecdsa-p256" -> ECDSA_P256_PUBLIC_KEY;
            case "ecdsa-p384" -> ECDSA_P384_PUBLIC_KEY;
            case "sk-ed25519" -> line("sk-ssh-ed25519@openssh.com", new byte[32], utf8("ssh:"));
            case "sk-ecdsa" -> line("sk-ecdsa-sha2-nistp256@openssh.com", utf8("nistp256"), new byte[65], utf8("ssh:"));
            case "ed25519-cert" -> line("ssh-ed25519-cert-v01@openssh.com", new byte[32], new byte[32]);
            case "unknown" -> line("ssh-foo", new byte[32]);
            default -> throw new IllegalArgumentException(type);
        };

        assertNull(Ec2KeyMaterial.readImportedKey(material), type);
    }

    /**
     * AWS lists 1024, 2048 and 4096 bits as the supported RSA lengths but imports others too, an
     * 8192-bit key among them (terraform-aws-modules/terraform-aws-key-pair#16), so Floci takes the
     * range OpenSSH reads, up to 16384 bits, which also covers ssh-keygen's default of 3072. The
     * moduli are synthetic: the length is what is checked, not whether the key factors.
     */
    @ParameterizedTest
    @CsvSource({"512, false", "1023, false", "1024, true", "2048, true", "3072, true", "4096, true",
            "8192, true", "16384, true", "16385, false"})
    void anRsaKeyIsImportedOnlyFrom1024To16384Bits(int bits, boolean accepted) {
        BigInteger modulus = BigInteger.ONE.shiftLeft(bits - 1).setBit(0);
        String material = line("ssh-rsa", BigInteger.valueOf(65537).toByteArray(), modulus.toByteArray());

        Ec2KeyMaterial.Imported key = Ec2KeyMaterial.readImportedKey(material);

        assertEquals(accepted, key != null, bits + " bits");
        if (accepted) {
            assertEquals("rsa", key.keyType());
            assertEquals(material, key.openSshPublicKey());
        }
    }

    @Test
    void aRealKeyOfTheShortestSupportedLengthIsImported() {
        assertEquals("rsa", Ec2KeyMaterial.readImportedKey(RSA_1024_PUBLIC_KEY).keyType());
    }

    /**
     * The blob has to be exactly the key of the type the line names, as OpenSSH requires: a field
     * missing, anything after the key, a type other than the line's, or a negative modulus is not a
     * key OpenSSH would read.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "rsa-blob-labelled-ed25519", "ed25519-blob-labelled-rsa", "rsa-without-modulus", "rsa-extra-field",
            "rsa-trailing-bytes", "ed25519-trailing-bytes", "ed25519-33-bytes", "rsa-negative-modulus",
            "rsa-empty-exponent"})
    void aKeyLineWhoseBlobIsNotExactlyTheKeyItNamesIsRefused(String shape) {
        byte[] rsaBlob = Base64.getDecoder().decode(RSA_PUBLIC_KEY.split(" ")[1]);
        byte[] ed25519Blob = Base64.getDecoder().decode(ED25519_PUBLIC_KEY.split(" ")[1]);
        byte[] exponent = BigInteger.valueOf(65537).toByteArray();
        byte[] modulus = BigInteger.ONE.shiftLeft(2047).setBit(0).toByteArray();
        String material = switch (shape) {
            case "rsa-blob-labelled-ed25519" -> "ssh-ed25519 " + base64(rsaBlob);
            case "ed25519-blob-labelled-rsa" -> "ssh-rsa " + base64(ed25519Blob);
            case "rsa-without-modulus" -> line("ssh-rsa", exponent);
            case "rsa-extra-field" -> line("ssh-rsa", exponent, modulus, utf8("extra"));
            case "rsa-trailing-bytes" -> "ssh-rsa " + base64(concat(rsaBlob, new byte[] {1, 2, 3}));
            case "ed25519-trailing-bytes" -> "ssh-ed25519 " + base64(concat(ed25519Blob, new byte[] {1, 2, 3}));
            case "ed25519-33-bytes" -> line("ssh-ed25519", new byte[33]);
            // Two's complement with the top bit set and no leading zero: a negative number.
            case "rsa-negative-modulus" -> line("ssh-rsa", exponent, Arrays.copyOfRange(modulus, 1, modulus.length));
            case "rsa-empty-exponent" -> line("ssh-rsa", new byte[0], modulus);
            default -> throw new IllegalArgumentException(shape);
        };

        assertNull(Ec2KeyMaterial.readImportedKey(material), shape);
    }

    /** A key pair holds one key: an instance launched with it would trust every line of the material. */
    @Test
    void materialHoldingASecondKeyLineIsRefused() {
        assertNull(Ec2KeyMaterial.readImportedKey(ED25519_PUBLIC_KEY + " first\n" + RSA_PUBLIC_KEY + " second\n"));
        assertNull(Ec2KeyMaterial.readImportedKey(RSA_PUBLIC_KEY + "\n\n" + RSA_PUBLIC_KEY));
    }

    /** RFC 4716 and DER are RSA-only formats for ImportKeyPair, and PEM has to label a public key. */
    @ParameterizedTest
    @ValueSource(strings = {
            "rfc4716-ed25519", "rfc4716-unterminated", "rfc4716-empty", "pem-private-key", "pem-certificate-label",
            "pem-mismatched-end", "pem-ec-key", "der-ec-key", "der-trailing-bytes"})
    void anRsaOnlyFormatHoldingAnythingButAnRsaPublicKeyIsRefused(String shape) throws Exception {
        String ecDer = base64(KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic().getEncoded());
        String material = switch (shape) {
            case "rfc4716-ed25519" -> "---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"throwaway\"\n"
                    + ED25519_PUBLIC_KEY.split(" ")[1] + "\n---- END SSH2 PUBLIC KEY ----\n";
            case "rfc4716-unterminated" -> RSA_RFC_4716.replace("---- END SSH2 PUBLIC KEY ----", "");
            case "rfc4716-empty" -> "---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"throwaway\"\n---- END SSH2 PUBLIC KEY ----";
            case "pem-private-key" -> Ec2KeyMaterial.generateRsa().privateKeyPem();
            case "pem-certificate-label" -> RSA_PEM_SPKI.replace("PUBLIC KEY", "CERTIFICATE");
            case "pem-mismatched-end" -> RSA_PEM_SPKI.replace("END PUBLIC KEY", "END RSA PUBLIC KEY");
            case "pem-ec-key" -> "-----BEGIN PUBLIC KEY-----\n" + ecDer + "\n-----END PUBLIC KEY-----\n";
            case "der-ec-key" -> ecDer;
            case "der-trailing-bytes" -> base64(concat(Base64.getDecoder().decode(RSA_DER_SPKI), new byte[] {0}));
            default -> throw new IllegalArgumentException(shape);
        };

        assertNull(Ec2KeyMaterial.readImportedKey(material), shape);
    }

    /**
     * Text that is not a key in any format, including the two mistakes AWS users report most: the
     * key line base64-encoded a second time, and a UTF-16 file.
     */
    @Test
    void materialThatIsNoKeyAtAllIsRefused() {
        assertNull(Ec2KeyMaterial.readImportedKey(null));
        assertNull(Ec2KeyMaterial.readImportedKey(""));
        assertNull(Ec2KeyMaterial.readImportedKey(" \r\n\t"));
        assertNull(Ec2KeyMaterial.readImportedKey("not-a-key"));
        assertNull(Ec2KeyMaterial.readImportedKey("ssh-rsa"));
        assertNull(Ec2KeyMaterial.readImportedKey("ssh-rsa !!!not-base64!!!"));
        assertNull(Ec2KeyMaterial.readImportedKey(base64(utf8(RSA_PUBLIC_KEY))));
        assertNull(Ec2KeyMaterial.readImportedKey(
                new String(RSA_PUBLIC_KEY.getBytes(StandardCharsets.UTF_16LE), StandardCharsets.UTF_8)));
    }

    /**
     * Every truncation and many corruptions of each accepted format, and random bytes, are read
     * without an exception escaping: ImportKeyPair has to answer them all with InvalidKey.Format.
     */
    @Test
    void noMaterialMakesTheReaderThrow() {
        Random random = new Random(20261020L);
        List<String> valid = List.of(RSA_PUBLIC_KEY + " c", ED25519_PUBLIC_KEY, RSA_RFC_4716, RSA_PEM_SPKI,
                RSA_PEM_PKCS1, RSA_DER_SPKI, RSA_DER_PKCS1, DSA_PUBLIC_KEY, ECDSA_P256_PUBLIC_KEY);
        for (String material : valid) {
            for (int end = 0; end <= material.length(); end++) {
                assertDoesNotThrow(material.substring(0, end));
            }
            for (int i = 0; i < 200; i++) {
                char[] chars = material.toCharArray();
                chars[random.nextInt(chars.length)] = (char) random.nextInt(128);
                assertDoesNotThrow(new String(chars));
            }
        }
        for (int i = 0; i < 2000; i++) {
            byte[] bytes = new byte[random.nextInt(600)];
            random.nextBytes(bytes);
            assertDoesNotThrow(new String(bytes, StandardCharsets.ISO_8859_1));
            assertDoesNotThrow("ssh-rsa " + base64(bytes));
            assertDoesNotThrow(base64(concat(new byte[] {0x30}, bytes)));
        }
    }

    /**
     * A SubjectPublicKeyInfo whose key BIT STRING declares unused pad bits holds no whole key. Bouncy
     * Castle answers such a bit string with an IllegalStateException rather than an IOException, which
     * escaped the reader as a 500 and, from a stored key pair, failed every DescribeKeyPairs.
     */
    @ParameterizedTest
    @CsvSource({"1, der", "1, pem", "4, der", "7, der", "7, pem"})
    void aDerKeyWhoseBitStringDeclaresPadBitsIsRefused(int padBits, String armour) {
        byte[] der = Base64.getDecoder().decode(RSA_DER_SPKI);
        // 30 82 01 22, then the 15-byte algorithm, then the BIT STRING header 03 82 01 0f: byte 23 is
        // the count of unused bits in the bit string's last byte.
        assertEquals(0, der[23]);
        der[23] = (byte) padBits;
        String material = "der".equals(armour) ? base64(der)
                : "-----BEGIN PUBLIC KEY-----\n" + base64(der) + "\n-----END PUBLIC KEY-----\n";

        assertNull(Ec2KeyMaterial.readImportedKey(material));
    }

    /**
     * Every byte of each binary form, set in turn to values that matter to DER and SSH framing, is
     * read without an exception escaping. Corrupting the base64 text, as the test above does, almost
     * never lands on a byte such as a bit string's pad count.
     */
    @Test
    void noByteOfABinaryKeyMakesTheReaderThrow() {
        int[] values = {0, 1, 2, 3, 4, 7, 8, 0x0f, 0x10, 0x1f, 0x30, 0x7f, 0x80, 0x81, 0x82, 0x84, 0xfe, 0xff};
        List<byte[]> binaries = List.of(Base64.getDecoder().decode(RSA_DER_SPKI), Base64.getDecoder().decode(RSA_DER_PKCS1),
                Base64.getDecoder().decode(RSA_PUBLIC_KEY.split(" ")[1]), Base64.getDecoder().decode(ED25519_PUBLIC_KEY.split(" ")[1]));
        for (int form = 0; form < binaries.size(); form++) {
            byte[] original = binaries.get(form);
            for (int position = 0; position < original.length; position++) {
                for (int value : values) {
                    byte[] corrupted = original.clone();
                    corrupted[position] = (byte) value;
                    String encoded = base64(corrupted);
                    if (form < 2) {
                        assertDoesNotThrow(encoded);
                        assertDoesNotThrow("-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----");
                    } else {
                        String type = form == 2 ? "ssh-rsa " : "ssh-ed25519 ";
                        assertDoesNotThrow(type + encoded);
                        assertDoesNotThrow("---- BEGIN SSH2 PUBLIC KEY ----\n" + encoded + "\n---- END SSH2 PUBLIC KEY ----");
                    }
                }
            }
        }
    }

    private static void assertDoesNotThrow(String material) {
        try {
            Ec2KeyMaterial.readImportedKey(material);
        } catch (RuntimeException e) {
            throw new AssertionError("readImportedKey threw for: " + material, e);
        }
    }

    /** The fingerprint ImportKeyPair records for the material, or null when it refuses it. */
    private static String fingerprintOf(String material) {
        Ec2KeyMaterial.Imported key = Ec2KeyMaterial.readImportedKey(material);
        return key != null ? key.fingerprint() : null;
    }

    /** An OpenSSH line of the given type whose blob holds the type and then these fields. */
    private static String line(String type, byte[]... fields) {
        return type + " " + base64(blobOf(type, fields));
    }

    private static byte[] blobOf(String type, byte[]... fields) {
        byte[] typeBytes = type.getBytes(StandardCharsets.UTF_8);
        int length = Integer.BYTES + typeBytes.length;
        for (byte[] field : fields) {
            length += Integer.BYTES + field.length;
        }
        ByteBuffer blob = ByteBuffer.allocate(length).putInt(typeBytes.length).put(typeBytes);
        for (byte[] field : fields) {
            blob.putInt(field.length).put(field);
        }
        return blob.array();
    }

    private static byte[] concat(byte[] first, byte[] second) {
        return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] read(ByteBuffer blob) {
        byte[] field = new byte[blob.getInt()];
        blob.get(field);
        return field;
    }
}
