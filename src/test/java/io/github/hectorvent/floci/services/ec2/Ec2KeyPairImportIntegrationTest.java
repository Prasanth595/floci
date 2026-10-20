package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * ImportKeyPair takes what AWS documents, RSA and ED25519 keys in the formats listed for each, and
 * refuses the rest with InvalidKey.Format; CreateKeyPair and ImportKeyPair hold names to the rule
 * AWS documents for both.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/create-key-pairs.html#how-to-generate-your-own-key-and-import-it-to-aws">Requirements for key pairs</a>
 */
@QuarkusTest
class Ec2KeyPairImportIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/ec2/aws4_request";

    private static final String ED25519_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj";

    /** Throwaway keys from ssh-keygen, of types EC2 does not import. */
    private static final String DSA_KEY = "ssh-dss AAAAB3NzaC1kc3MAAACBAOFToDsRbL7Z8AqAZOKXROc1Cy5SA1ZgM5odRC57MSEruUj"
            + "fUv2//jGbOLemgQBFsqZY1dyKlrpR3dm+tpfXGQR26Pkqm+5BF8kaykQZAYdwCzRhwzM7c8pei36Qi2XJ1s+MeLbIXZ2TTfyvkYhckXSrn"
            + "f90oVJaMpaTIiDzu/CVAAAAFQCk1LBHtzCpTFHCInI4jnrGMjyyQQAAAIBz+pU2CQ0d8tQyxfQJ71wCCowSoVuUk5Zh1kAnIOoSPOTOuqo"
            + "s4OqicYdSH/AK8G0gKoXbp1BJX8H1yB401LAl8gccL1YbZBheZAtfNjeBd1xOZQZXWSYOEPQWVMcrqkId3un13z55qV0vl6ohU3Mp9ekGjB"
            + "HuQWhDk8RYGgL9vwAAAIB6eL+huYUrgr06l0ozSlF9pf87rehfJaCLLJPPco8F9uCqx8sYsi/8b6gkbfq4w/k75H62ew8LVhpab9bP3/6/HQ"
            + "odnGrh1rthcapynsUzpFcjS86okexUcDkR3OnPLJ3CNGBS7MM0yw+NeXZN2BkeTdKAfbAdFw1mrb/AKu9GDg== throwaway";
    private static final String ECDSA_KEY = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABB"
            + "BFaxjAHsoCE0FmjbKLGzgVRKfOKFmYj1/JpnSjsuLjy23mHIUPkLJ5thgE47igHp+KufC1V2KqJE4OP5w4EK9SU= throwaway";

    /**
     * Each refusal is the error AWS gives an import it cannot read, and stores nothing: DSA and
     * ECDSA keys, an RSA key shorter than 1024 bits, the truncated ssh-rsa blob Floci's tests once
     * imported, two key lines, an ED25519 key in RFC 4716 (an RSA-only format), a private key, and
     * a key line base64-encoded twice.
     */
    @ParameterizedTest
    @ValueSource(strings = {"dsa", "ecdsa", "rsa-768", "truncated-rsa", "two-lines", "rfc4716-ed25519",
            "private-key", "double-base64", "der-pad-bits", "der-empty-bit-string"})
    void materialEc2DoesNotImportIsRefusedWithInvalidKeyFormat(String shape) {
        String name = "refused-" + UUID.randomUUID();
        String material = switch (shape) {
            case "dsa" -> DSA_KEY;
            case "ecdsa" -> ECDSA_KEY;
            case "rsa-768" -> sshRsaLine(BigInteger.ONE.shiftLeft(767).setBit(0));
            // A SubjectPublicKeyInfo whose key bit string declares an unused bit: no whole key.
            case "der-pad-bits" -> base64(withPadBits(Ec2KeyMaterial.generateRsa().openSshPublicKey()));
            // An rsaEncryption SubjectPublicKeyInfo whose key bit string is empty: no key at all.
            case "der-empty-bit-string" -> "MBIwDQYJKoZIhvcNAQEBBQADAQA=";
            case "truncated-rsa" -> "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAQQDHnLTTbpnrFWkfVt test@test";
            case "two-lines" -> ED25519_KEY + " first\n" + Ec2KeyMaterial.generateRsa().openSshPublicKey() + " second\n";
            case "rfc4716-ed25519" -> "---- BEGIN SSH2 PUBLIC KEY ----\n" + ED25519_KEY.split(" ")[1]
                    + "\n---- END SSH2 PUBLIC KEY ----\n";
            case "private-key" -> Ec2KeyMaterial.generateRsa().privateKeyPem();
            case "double-base64" -> base64(utf8(Ec2KeyMaterial.generateRsa().openSshPublicKey()));
            default -> throw new IllegalArgumentException(shape);
        };

        importKeyPair(name, material).statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidKey.Format"))
                .body("Response.Errors.Error.Message", equalTo("Key is not in valid OpenSSH public key format"));
        given().header("Authorization", AUTH)
                .formParam("Action", "DescribeKeyPairs").formParam("KeyName.1", name)
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidKeyPair.NotFound"));
    }

    /**
     * One RSA key imported as an OpenSSH line, as RFC 4716, as PEM and as bare base64 DER is the same
     * key each time: the fingerprint is the MD5 of its DER, as AWS reports for an imported RSA key.
     */
    @ParameterizedTest
    @ValueSource(strings = {"openssh", "rfc4716", "pem", "der"})
    void anRsaKeyIsImportedFromEachFormatAwsDocuments(String format) throws Exception {
        String openSsh = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        String blob = openSsh.split(" ")[1];
        RSAPublicKey key = rsaKeyOf(Base64.getDecoder().decode(blob));
        String der = base64(key.getEncoded());
        String material = switch (format) {
            case "openssh" -> openSsh + " someone@example.com\n";
            case "rfc4716" -> "---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"throwaway\"\n"
                    + String.join("\n", blob.split("(?<=\\G.{70})")) + "\n---- END SSH2 PUBLIC KEY ----\n";
            case "pem" -> "-----BEGIN PUBLIC KEY-----\n" + String.join("\n", der.split("(?<=\\G.{64})"))
                    + "\n-----END PUBLIC KEY-----\n";
            case "der" -> der;
            default -> throw new IllegalArgumentException(format);
        };
        String name = "imported-" + format + "-" + UUID.randomUUID();
        String expected = HexFormat.of().withDelimiter(":")
                .formatHex(MessageDigest.getInstance("MD5").digest(key.getEncoded()));

        String id = importKeyPair(name, material).statusCode(200)
                .body("ImportKeyPairResponse.keyName", equalTo(name))
                .body("ImportKeyPairResponse.keyFingerprint", equalTo(expected))
                .extract().path("ImportKeyPairResponse.keyPairId");
        try {
            given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeKeyPairs").formParam("KeyPairId.1", id)
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.keyFingerprint", equalTo(expected))
                    .body("DescribeKeyPairsResponse.keySet.item.keyType", equalTo("rsa"));
        } finally {
            deleteKeyPair(id);
        }
    }

    /**
     * CreateKeyPair and ImportKeyPair take a name of up to 255 ASCII characters with no leading or
     * trailing space, and answer any other with InvalidParameterValue.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void keyPairNamesAreHeldToTheRuleAwsDocuments(boolean imported) {
        String longest = ("k-" + UUID.randomUUID()).repeat(10).substring(0, 255);
        String id = createOrImport(imported, longest).statusCode(200)
                .body((imported ? "ImportKeyPairResponse" : "CreateKeyPairResponse") + ".keyName", equalTo(longest))
                .extract().path((imported ? "ImportKeyPairResponse" : "CreateKeyPairResponse") + ".keyPairId");
        deleteKeyPair(id);

        for (String name : new String[] {longest + "k", "cl\u00e9-" + UUID.randomUUID(), " leading-" + UUID.randomUUID(),
                "trailing-" + UUID.randomUUID() + " "}) {
            createOrImport(imported, name).statusCode(400)
                    .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"));
        }
    }

    @Test
    void anOverlongNameIsRefusedWithTheValueAndTheReason() {
        String name = "n".repeat(256);
        createOrImport(false, name).statusCode(400)
                .body("Response.Errors.Error.Message", equalTo(
                        "Value (" + name + ") for parameter KeyName is invalid. Length exceeds maximum of 255 characters."));
    }

    private static ValidatableResponse createOrImport(boolean imported, String name) {
        return imported
                ? importKeyPair(name, Ec2KeyMaterial.generateRsa().openSshPublicKey())
                : given().header("Authorization", AUTH)
                        .formParam("Action", "CreateKeyPair").formParam("KeyName", name)
                        .post("/").then();
    }

    private static ValidatableResponse importKeyPair(String name, String material) {
        return given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", base64(utf8(material)))
                .post("/").then();
    }

    private static void deleteKeyPair(String id) {
        given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                .formParam("KeyPairId", id).post("/").then().statusCode(200);
    }

    /** An ssh-rsa line for a key with this modulus and the usual exponent, 65537. */
    private static String sshRsaLine(BigInteger modulus) {
        byte[] type = utf8("ssh-rsa");
        byte[] exponent = BigInteger.valueOf(65537).toByteArray();
        byte[] n = modulus.toByteArray();
        ByteBuffer blob = ByteBuffer.allocate(12 + type.length + exponent.length + n.length);
        for (byte[] field : new byte[][] {type, exponent, n}) {
            blob.putInt(field.length).put(field);
        }
        return "ssh-rsa " + base64(blob.array());
    }

    /**
     * The DER SubjectPublicKeyInfo of a 2048-bit OpenSSH RSA key with its bit string's pad count, the
     * byte after the BIT STRING header, set to one.
     */
    private static byte[] withPadBits(String openSshRsaKey) {
        try {
            byte[] der = rsaKeyOf(Base64.getDecoder().decode(openSshRsaKey.split(" ")[1])).getEncoded();
            der[23] = 1;
            return der;
        } catch (Exception e) {
            throw new IllegalStateException("could not encode the test key", e);
        }
    }

    /** The RSA key of an ssh-rsa blob: its type, then the exponent, then the modulus. */
    private static RSAPublicKey rsaKeyOf(byte[] blob) throws Exception {
        ByteBuffer fields = ByteBuffer.wrap(blob);
        fields.position(fields.getInt() + Integer.BYTES);
        byte[] exponent = new byte[fields.getInt()];
        fields.get(exponent);
        byte[] modulus = new byte[fields.getInt()];
        fields.get(modulus);
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(new BigInteger(modulus), new BigInteger(exponent)));
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
