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
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class Ec2KeyPairPublicKeyIntegrationTest {
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261009/us-east-1/ec2/aws4_request";

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
                    (importedKey + " supplied-comment").getBytes(StandardCharsets.UTF_8)));
        }
        String responseName = imported ? "ImportKeyPairResponse" : "CreateKeyPairResponse";
        XmlPath created = create.post("/").then().statusCode(200).extract().xmlPath();
        String id = created.getString(responseName + ".keyPairId");
        String expected = (imported ? importedKey : publicHalfOf(created.getString(responseName + ".keyMaterial")))
                + " " + name;
        try {
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
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
    }

    /** The OpenSSH line, without a comment, of the public half of a PEM private key. */
    private static String publicHalfOf(String privateKeyPem) throws IOException {
        try (PEMParser parser = new PEMParser(new StringReader(privateKeyPem))) {
            PEMKeyPair pair = (PEMKeyPair) parser.readObject();
            return SshPublicKeys.toOpenSsh((RSAPublicKey) new JcaPEMKeyConverter().getKeyPair(pair).getPublic());
        }
    }

    /**
     * An OpenSSH line is recognised without a comment and whatever whitespace separates or ends its
     * fields, and comes back in the canonical single-space form with the key-pair name added.
     */
    @Test
    void anOpenSshLineWithoutACommentIsDescribedWithTheKeyPairName() {
        String name = "public-key-" + UUID.randomUUID();
        String blob = "AAAAC3NzaC1lZDI1NTE5AAAAIIj7azlDjVHAsSxgcpCRZ3oWnTm0nAFM64y9jd22ioI/";
        String id = given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(
                        ("ssh-ed25519\t" + blob + "\r\n").getBytes(StandardCharsets.UTF_8)))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", "true")
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.publicKey", equalTo("ssh-ed25519 " + blob + " " + name));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
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
                ? ("---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"" + name + "\"\n" + openSsh.split(" ")[1]
                        + "\n---- END SSH2 PUBLIC KEY ----\n").getBytes(StandardCharsets.UTF_8)
                : (openSsh + " " + name).getBytes(StandardCharsets.UTF_16LE);
        String id = given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(material))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", "true")
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id))
                    .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                    .body("DescribeKeyPairsResponse.keySet.item.keyFingerprint", startsWith("00:00"))
                    .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
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
        String id = given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(
                        (type + " " + blob + " " + name).getBytes(StandardCharsets.UTF_8)))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", "true")
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id))
                    .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
    }

    /**
     * A line that names another type than its blob declares, here a whole ssh-rsa blob labelled
     * ssh-ed25519, is not described as the RSA key its blob holds.
     */
    @Test
    void aLineLabelledWithAnotherTypeThanItsBlobIsDescribedWithoutAPublicKey() {
        String name = "public-key-" + UUID.randomUUID();
        String rsaBlob = Ec2KeyMaterial.generateRsa().openSshPublicKey().split(" ")[1];
        String id = given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(
                        ("ssh-ed25519 " + rsaBlob + " " + name).getBytes(StandardCharsets.UTF_8)))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
        try {
            given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", "true")
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id))
                    .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(0));
        } finally {
            given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair")
                    .formParam("KeyPairId", id).post("/").then().statusCode(200);
        }
    }
}
