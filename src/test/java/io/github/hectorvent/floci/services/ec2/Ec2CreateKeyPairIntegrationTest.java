package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.Digests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.security.interfaces.RSAPrivateCrtKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CreateKeyPair's KeyType and KeyFormat over the Query protocol. AWS creates an ED25519 key for
 * KeyType=ed25519 and returns either type as a PuTTY file for KeyFormat=ppk; the fingerprint
 * follows the key's type, not its format, and a value outside either parameter's enum is refused.
 *
 * <p>The material is parsed and checked against the fingerprint and the public key DescribeKeyPairs
 * reports, rather than matched against a header alone.
 */
@QuarkusTest
class Ec2CreateKeyPairIntegrationTest {

    static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/ec2/aws4_request";

    @Test
    void ed25519IsAnOpenSshPrivateKeyFingerprintedWithSha256() throws IOException {
        String name = uniqueName();
        XmlPath created = createKeyPair(name, "ed25519", null).statusCode(200).extract().xmlPath();
        String id = created.getString("CreateKeyPairResponse.keyPairId");
        try {
            String material = created.getString("CreateKeyPairResponse.keyMaterial");
            assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", firstLine(material));
            assertTrue(material.endsWith("-----END OPENSSH PRIVATE KEY-----\n"), "the block ends its last line");
            byte[] blob = sshEd25519Blob(openSshEd25519Key(material));

            // "the base64-encoded SHA-256 digest", padded as AWS reports it
            assertEquals(Base64.getEncoder().encodeToString(Digests.sha256(blob)),
                    created.getString("CreateKeyPairResponse.keyFingerprint"));
            assertDescribedAs(name, id, blob, "ed25519");
        } finally {
            deleteKeyPair(id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rsa", "ed25519"})
    void ppkIsAPuttyFileOfTheKeyWithTheNameAsItsComment(String keyType) throws Exception {
        String name = uniqueName();
        XmlPath created = createKeyPair(name, keyType, "ppk").statusCode(200).extract().xmlPath();
        String id = created.getString("CreateKeyPairResponse.keyPairId");
        try {
            PuttyKeyFile file = PuttyKeyFile.parse(created.getString("CreateKeyPairResponse.keyMaterial"));
            assertEquals("rsa".equals(keyType) ? "ssh-rsa" : "ssh-ed25519", file.algorithm());
            assertEquals("none", file.encryption());
            assertEquals(name, file.comment());
            assertTrue(file.macIsValid(), "Private-MAC is PuTTY's HMAC-SHA1 over the file");

            // A key EC2 creates is fingerprinted from the key, whichever format it is returned in.
            String fingerprint = "rsa".equals(keyType)
                    ? colonHex(Digests.sha1(file.rsaKey().getEncoded()))
                    : Base64.getEncoder().encodeToString(Digests.sha256(sshEd25519Blob(file.ed25519Key())));
            assertEquals(fingerprint, created.getString("CreateKeyPairResponse.keyFingerprint"));
            assertDescribedAs(name, id, file.publicBlob(), keyType);
        } finally {
            deleteKeyPair(id);
        }
    }

    @Test
    void rsaPemIsStillThePkcs1KeyFingerprintedWithSha1() throws IOException {
        String name = uniqueName();
        XmlPath created = createKeyPair(name, "rsa", "pem").statusCode(200).extract().xmlPath();
        String id = created.getString("CreateKeyPairResponse.keyPairId");
        try {
            String material = created.getString("CreateKeyPairResponse.keyMaterial");
            assertEquals("-----BEGIN RSA PRIVATE KEY-----", firstLine(material));
            Object parsed = new PEMParser(new StringReader(material)).readObject();
            RSAPrivateCrtKey key = (RSAPrivateCrtKey) new JcaPEMKeyConverter()
                    .getKeyPair((PEMKeyPair) parsed).getPrivate();
            assertEquals(2048, key.getModulus().bitLength());
            // "the SHA-1 digest of the DER encoded private key", PKCS#8 as openssl pkcs8 -topk8 writes it
            assertEquals(colonHex(Digests.sha1(key.getEncoded())),
                    created.getString("CreateKeyPairResponse.keyFingerprint"));
        } finally {
            deleteKeyPair(id);
        }
    }

    @Test
    void anUnknownKeyTypeIsRefusedAndNothingIsCreated() {
        String name = uniqueName();
        createKeyPair(name, "ecdsa", null)
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("1 validation error detected: Value 'ecdsa' at "
                        + "'keyType' failed to satisfy constraint: Member must satisfy enum value set: [rsa, ed25519]"));
        assertNotCreated(name);
    }

    @Test
    void anUnknownKeyFormatIsRefusedAndNothingIsCreated() {
        String name = uniqueName();
        createKeyPair(name, null, "der")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("1 validation error detected: Value 'der' at "
                        + "'keyFormat' failed to satisfy constraint: Member must satisfy enum value set: [ppk, pem]"));
        assertNotCreated(name);
    }

    @Test
    void anUnknownKeyTypeAndFormatAreReportedTogether() {
        String name = uniqueName();
        createKeyPair(name, "ecdsa", "der")
                .statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("2 validation errors detected: Value 'ecdsa' at "
                        + "'keyType' failed to satisfy constraint: Member must satisfy enum value set: [rsa, ed25519]; "
                        + "Value 'der' at 'keyFormat' failed to satisfy constraint: Member must satisfy enum value set: "
                        + "[ppk, pem]"));
        assertNotCreated(name);
    }

    @Test
    void anUnknownKeyTypeIsRefusedBeforeTheNameIsFoundTaken() {
        String name = uniqueName();
        String id = createKeyPair(name, null, null).statusCode(200).extract().path("CreateKeyPairResponse.keyPairId");
        try {
            createKeyPair(name, "dsa", null)
                    .statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        } finally {
            deleteKeyPair(id);
        }
    }

    /**
     * DescribeKeyPairs reports the public half of the created key with the key-pair name as its
     * comment, and, where it reports a type at all, the type that was created.
     */
    private static void assertDescribedAs(String name, String id, byte[] blob, String keyType) {
        String type = "rsa".equals(keyType) ? "ssh-rsa" : "ssh-ed25519";
        XmlPath described = given().header("Authorization", AUTH)
                .formParam("Action", "DescribeKeyPairs")
                .formParam("KeyPairId.1", id)
                .formParam("IncludePublicKey", "true")
                .post("/").then().statusCode(200)
                .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                .body("DescribeKeyPairsResponse.keySet.item.publicKey",
                        equalTo(type + " " + Base64.getEncoder().encodeToString(blob) + " " + name))
                .extract().xmlPath();
        String reportedType = described.getString("DescribeKeyPairsResponse.keySet.item.keyType");
        assertTrue(reportedType.isEmpty() || reportedType.equals(keyType),
                "an " + keyType + " key pair is described as " + reportedType);
    }

    private static void assertNotCreated(String name) {
        given().header("Authorization", AUTH)
                .formParam("Action", "DescribeKeyPairs")
                .formParam("KeyName.1", name)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidKeyPair.NotFound"));
    }

    /** CreateKeyPair with this name, and the KeyType and KeyFormat when they are not null. */
    static ValidatableResponse createKeyPair(String name, String keyType, String keyFormat) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", "CreateKeyPair")
                .formParam("KeyName", name);
        if (keyType != null) {
            request.formParam("KeyType", keyType);
        }
        if (keyFormat != null) {
            request.formParam("KeyFormat", keyFormat);
        }
        return request.post("/").then();
    }

    static void deleteKeyPair(String id) {
        given().header("Authorization", AUTH)
                .formParam("Action", "DeleteKeyPair")
                .formParam("KeyPairId", id)
                .post("/").then().statusCode(200);
    }

    /** The ED25519 key of an OpenSSH private key file, read with Bouncy Castle's OpenSSH reader. */
    static Ed25519PrivateKeyParameters openSshEd25519Key(String material) throws IOException {
        try (PemReader reader = new PemReader(new StringReader(material))) {
            PemObject pem = reader.readPemObject();
            assertEquals("OPENSSH PRIVATE KEY", pem.getType());
            AsymmetricKeyParameter key = OpenSSHPrivateKeyUtil.parsePrivateKeyBlob(pem.getContent());
            assertTrue(key instanceof Ed25519PrivateKeyParameters, "expected an ED25519 key, got " + key);
            return (Ed25519PrivateKeyParameters) key;
        }
    }

    /** The OpenSSH public key blob of an ED25519 key. */
    static byte[] sshEd25519Blob(Ed25519PrivateKeyParameters key) throws IOException {
        return OpenSSHPublicKeyUtil.encodePublicKey(key.generatePublicKey());
    }

    private static String colonHex(byte[] digest) {
        return HexFormat.of().withDelimiter(":").formatHex(digest);
    }

    /** The first line of key material, which a failure message can show without disclosing the key. */
    private static String firstLine(String material) {
        return material.lines().findFirst().orElse("");
    }

    private static String uniqueName() {
        return "create-key-pair-" + UUID.randomUUID();
    }
}
