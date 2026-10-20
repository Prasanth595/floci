package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.SshPublicKeys;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2PuttyKeysTest {

    /**
     * The unencrypted version 2 file PuTTY's own test suite (test/cryptsuite.py, testPPKLoadSave)
     * checks its writer against, byte for byte, for {@link Ec2Ed25519KeysTest#PUTTY_TEST_KEY}.
     */
    private static final String PUTTY_TEST_FILE = """
            PuTTY-User-Key-File-2: ssh-ed25519
            Encryption: none
            Comment: ed25519-key-20200105
            Public-Lines: 2
            AAAAC3NzaC1lZDI1NTE5AAAAIHJCszOHaI9X/yGLtjn22f0hO6VPMQDVtctkym6F
            JH1W
            Private-Lines: 1
            AAAAIGvvIpl8jyqn8Xufkw6v3FnEGtXF3KWw55AP3/AGEBpY
            Private-MAC: 2a629acfcfbe28488a1ba9b6948c36406bc28422
            """;

    @Test
    void anEd25519KeyIsWrittenExactlyAsPuttyWritesIt() {
        Ec2KeyMaterial.Generated pem = Ec2Ed25519Keys.of(
                new Ed25519PrivateKeyParameters(Ec2Ed25519KeysTest.PUTTY_TEST_KEY, 0));

        Ec2KeyMaterial.Generated ppk = Ec2PuttyKeys.of(pem, "ed25519-key-20200105");

        assertEquals(PUTTY_TEST_FILE, ppk.privateKeyPem());
        assertEquals(pem.openSshPublicKey(), ppk.openSshPublicKey());
        assertEquals(pem.fingerprint(), ppk.fingerprint());
    }

    @Test
    void anEd25519FileHoldsTheKeyOfThePem() throws Exception {
        Ec2KeyMaterial.Generated pem = Ec2KeyMaterial.generate("ed25519", "pem", "name");

        PuttyKeyFile file = PuttyKeyFile.parse(Ec2PuttyKeys.of(pem, "name").privateKeyPem());

        assertEquals("ssh-ed25519", file.algorithm());
        assertTrue(file.macIsValid());
        assertArrayEquals(Ec2CreateKeyPairIntegrationTest.openSshEd25519Key(pem.privateKeyPem()).getEncoded(),
                file.ed25519Key().getEncoded());
        assertArrayEquals(SshPublicKeys.decodeBlob(pem.openSshPublicKey()), file.publicBlob());
    }

    /**
     * PuTTY keeps an RSA key's larger prime first, and Java's generator puts either first, so both
     * orders are checked: the file holds the PEM key with the larger prime first, and is
     * fingerprinted by the SHA-1 of that key's DER, which is what PuTTYgen exports from it.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void anRsaFileHoldsTheKeyOfThePemWithTheLargerPrimeFirst(boolean largerPrimeFirst) throws Exception {
        RSAPrivateCrtKey generated = rsaKeyWithLargerPrimeFirst(largerPrimeFirst);
        Ec2KeyMaterial.Generated pem = new Ec2KeyMaterial.Generated(pkcs1Pem(generated),
                SshPublicKeys.toOpenSsh(publicHalf(generated)), "fingerprint of the PEM key");

        Ec2KeyMaterial.Generated ppk = Ec2PuttyKeys.of(pem, "rsa-name");
        PuttyKeyFile file = PuttyKeyFile.parse(ppk.privateKeyPem());

        assertEquals("ssh-rsa", file.algorithm());
        assertEquals("none", file.encryption());
        assertEquals("rsa-name", file.comment());
        assertTrue(file.macIsValid());
        RSAPrivateCrtKey written = file.rsaKey();
        assertEquals(generated.getModulus(), written.getModulus());
        assertEquals(generated.getPrivateExponent(), written.getPrivateExponent());
        assertEquals(generated.getPrimeP().max(generated.getPrimeQ()), written.getPrimeP());
        assertArrayEquals(SshPublicKeys.decodeBlob(pem.openSshPublicKey()), file.publicBlob());
        assertEquals(pem.openSshPublicKey(), ppk.openSshPublicKey());
        assertEquals(HexFormat.of().withDelimiter(":").formatHex(
                MessageDigest.getInstance("SHA-1").digest(written.getEncoded())), ppk.fingerprint());
    }

    @Test
    void aGeneratedRsaKeyFileHoldsA2048BitKeyFingerprintedAsWritten() throws Exception {
        Ec2KeyMaterial.Generated ppk = Ec2KeyMaterial.generate("rsa", "ppk", "name");
        PuttyKeyFile file = PuttyKeyFile.parse(ppk.privateKeyPem());

        assertEquals(2048, file.rsaKey().getModulus().bitLength());
        assertEquals(HexFormat.of().withDelimiter(":").formatHex(
                MessageDigest.getInstance("SHA-1").digest(file.rsaKey().getEncoded())), ppk.fingerprint());
    }

    @Test
    void aNameThatCannotBeOneLineOfTheFileIsLeftOut() throws Exception {
        Ec2KeyMaterial.Generated pem = Ec2KeyMaterial.generate("ed25519", "pem", "ignored");

        PuttyKeyFile file = PuttyKeyFile.parse(Ec2PuttyKeys.of(pem, "two\nlines").privateKeyPem());

        assertEquals("", file.comment());
        assertTrue(file.macIsValid());
    }

    private static RSAPrivateCrtKey rsaKeyWithLargerPrimeFirst(boolean largerPrimeFirst) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateCrtKey key = (RSAPrivateCrtKey) generator.generateKeyPair().getPrivate();
        if ((key.getPrimeP().compareTo(key.getPrimeQ()) > 0) == largerPrimeFirst) {
            return key;
        }
        BigInteger p = key.getPrimeQ();
        BigInteger q = key.getPrimeP();
        BigInteger d = key.getPrivateExponent();
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(
                key.getModulus(), key.getPublicExponent(), d, p, q, d.mod(p.subtract(BigInteger.ONE)),
                d.mod(q.subtract(BigInteger.ONE)), q.modInverse(p)));
    }

    private static RSAPublicKey publicHalf(RSAPrivateCrtKey key) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
    }

    private static String pkcs1Pem(RSAPrivateCrtKey key) throws IOException {
        StringWriter out = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(out)) {
            writer.writeObject(key);
        }
        return out.toString();
    }
}
