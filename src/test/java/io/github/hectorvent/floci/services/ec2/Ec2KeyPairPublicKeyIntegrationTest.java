package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.SshPublicKeys;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class Ec2KeyPairPublicKeyIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261009/us-east-1/ec2/aws4_request";

    /** The blob of a throwaway ed25519 public key. */
    private static final String ED25519_BLOB = "AAAAC3NzaC1lZDI1NTE5AAAAIIj7azlDjVHAsSxgcpCRZ3oWnTm0nAFM64y9jd22ioI/";

    /**
     * The described key is the public half of the key the caller holds, with the key-pair name as
     * its comment in place of any the import carried: the line that was imported, or the half of
     * the private key CreateKeyPair returned. It is there only when IncludePublicKey is true.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publicKeyIsReturnedOnlyWhenRequested(boolean imported) throws IOException {
        String name = "public-key-" + UUID.randomUUID();
        String importedKey = imported ? Ec2KeyMaterial.generateRsa().openSshPublicKey() : null;
        RequestSpecification create = given().header("Authorization", AUTH)
                .formParam("Action", imported ? "ImportKeyPair" : "CreateKeyPair")
                .formParam("KeyName", name);
        if (imported) {
            create.formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(
                    utf8(importedKey + " supplied-comment")));
        }
        String responseName = imported ? "ImportKeyPairResponse" : "CreateKeyPairResponse";
        XmlPath created = create.post("/").then().statusCode(200).extract().xmlPath();
        String id = created.getString(responseName + ".keyPairId");
        try {
            String expected = (imported ? importedKey : publicHalfOf(created.getString(responseName + ".keyMaterial")))
                    + " " + name;
            for (String selection : new String[]{"KeyName.1", "KeyPairId.1"}) {
                String value = selection.startsWith("KeyName") ? name : id;
                for (String include : new String[]{null, "false", "true"}) {
                    RequestSpecification describe = given().header("Authorization", AUTH)
                            .formParam("Action", "DescribeKeyPairs").formParam(selection, value);
                    if (include != null) {
                        describe.formParam("IncludePublicKey", include);
                    }
                    ValidatableResponse response = describe.post("/").then().statusCode(200)
                            .body("DescribeKeyPairsResponse.keySet.item.size()", equalTo(1))
                            .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                            .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id))
                            .body("DescribeKeyPairsResponse.keySet.item.keyMaterial.size()", equalTo(0))
                            .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()",
                                    equalTo("true".equals(include) ? 1 : 0));
                    if ("true".equals(include)) {
                        response.body("DescribeKeyPairsResponse.keySet.item.publicKey", equalTo(expected));
                    }
                }
            }
        } finally {
            deleteKeyPair(id);
        }
    }

    /**
     * An OpenSSH line is recognised without a comment and whatever whitespace separates or ends its
     * fields, and comes back in the canonical single-space form with the key-pair name added.
     */
    @Test
    void anOpenSshLineWithoutACommentIsDescribedWithTheKeyPairName() {
        String name = "public-key-" + UUID.randomUUID();
        describeImported(name, utf8("ssh-ed25519\t" + ED25519_BLOB + "\r\n"), response -> response
                .body("DescribeKeyPairsResponse.keySet.item.publicKey",
                        equalTo("ssh-ed25519 " + ED25519_BLOB + " " + name)));
    }

    /**
     * ImportKeyPair keeps material it cannot read as it was given, so the key pair is described
     * without a public key rather than with a rewrite of text that is not an OpenSSH line. A UTF-16
     * file decodes to text with NUL characters, which XML cannot carry at all.
     */
    @ParameterizedTest
    @ValueSource(strings = {"rfc4716", "utf16"})
    void materialThatIsNotAnOpenSshLineIsDescribedWithoutAPublicKey(String format) {
        String name = "public-key-" + UUID.randomUUID();
        String openSsh = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        byte[] material = "rfc4716".equals(format)
                ? utf8("---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"" + name + "\"\n" + openSsh.split(" ")[1]
                        + "\n---- END SSH2 PUBLIC KEY ----\n")
                : (openSsh + " " + name).getBytes(StandardCharsets.UTF_16LE);
        describeImported(name, material, response -> response
                .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                .body("DescribeKeyPairsResponse.keySet.item.keyFingerprint", startsWith("00:00"))
                .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0)));
    }

    /**
     * A blob that declares a key type but lacks a field that type requires, here an ssh-rsa type
     * with no exponent or modulus and an ed25519 key one byte short, is not a public key.
     */
    @ParameterizedTest
    @ValueSource(strings = {"AAAAB3NzaC1yc2E=", "AAAAC3NzaC1lZDI1NTE5AAAAHwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
    void aBlobMissingAFieldItsTypeRequiresIsDescribedWithoutAPublicKey(String blob) {
        String name = "public-key-" + UUID.randomUUID();
        String type = blob.startsWith("AAAAB3") ? "ssh-rsa" : "ssh-ed25519";
        describeImported(name, utf8(type + " " + blob + " " + name), response -> response
                .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0)));
    }

    /**
     * A blob with anything after its key, here a whole extra field or three stray bytes after an
     * ed25519 key, is one OpenSSH refuses, so it is not described as a key either.
     */
    @ParameterizedTest
    @ValueSource(strings = {"field", "bytes"})
    void aBlobWithAnythingAfterItsKeyIsDescribedWithoutAPublicKey(String trailing) {
        String name = "public-key-" + UUID.randomUUID();
        byte[] key = Base64.getDecoder().decode(ED25519_BLOB);
        byte[] extra = "field".equals(trailing) ? new byte[] {0, 0, 0, 5, 'e', 'x', 't', 'r', 'a'} : new byte[] {1, 2, 3};
        String blob = Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(key.length + extra.length).put(key).put(extra).array());
        describeImported(name, utf8("ssh-ed25519 " + blob + " " + name), response -> response
                .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0)));
    }

    /**
     * A line that names another type than its blob declares, here a whole ssh-rsa blob labelled
     * ssh-ed25519, is not described as the RSA key its blob holds.
     */
    @Test
    void aLineLabelledWithAnotherTypeThanItsBlobIsDescribedWithoutAPublicKey() {
        String name = "public-key-" + UUID.randomUUID();
        String rsaBlob = Ec2KeyMaterial.generateRsa().openSshPublicKey().split(" ")[1];
        describeImported(name, utf8("ssh-ed25519 " + rsaBlob + " " + name), response -> response
                .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0)));
    }

    /**
     * Material holding several key lines is not one key pair's key: an instance launched with it
     * trusts every line, so describing only the first would hide the others.
     */
    @Test
    void materialHoldingSeveralKeysIsDescribedWithoutAPublicKey() {
        String name = "public-key-" + UUID.randomUUID();
        String second = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        describeImported(name, utf8("ssh-ed25519 " + ED25519_BLOB + " first\n" + second + " second\n"),
                response -> response.body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0)));
    }

    /**
     * Imports the material under the given name, describes the key pair with IncludePublicKey=true
     * and hands the response to the check, then deletes the key pair whatever the check found.
     */
    private static void describeImported(String name, byte[] material, Consumer<ValidatableResponse> check) {
        String id = given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(material))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
        try {
            check.accept(given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", "true")
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id)));
        } finally {
            deleteKeyPair(id);
        }
    }

    private static void deleteKeyPair(String id) {
        given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                .formParam("KeyPairId", id).post("/").then().statusCode(200);
    }

    /** The OpenSSH line, without a comment, of the public half of a PEM private key. */
    private static String publicHalfOf(String privateKeyPem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(privateKeyPem))) {
            PEMKeyPair pair = (PEMKeyPair) parser.readObject();
            return SshPublicKeys.toOpenSsh((RSAPublicKey) new JcaPEMKeyConverter().getKeyPair(pair).getPublic());
        }
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
