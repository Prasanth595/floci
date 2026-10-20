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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Consumer;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

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
     * ImportKeyPair takes a name with a line break, and as a comment that name would turn the one
     * key into lines an authorized_keys file reads as further keys, so the key is described bare.
     */
    @Test
    void aNameWithALineBreakIsNotUsedAsTheComment() {
        String name = "public-key-" + UUID.randomUUID() + "\nssh-ed25519 " + ED25519_BLOB + " another";
        describeImported(name, utf8("ssh-ed25519 " + ED25519_BLOB), response -> response
                .body("DescribeKeyPairsResponse.keySet.item.publicKey", equalTo("ssh-ed25519 " + ED25519_BLOB)));
    }

    /**
     * ImportKeyPair reads an RSA key from RFC 4716 and stores it as its OpenSSH line, so the key
     * pair is described with that line and its name, as one imported as an OpenSSH line would be.
     * The material ImportKeyPair refuses can only be held by a key pair saved by an earlier version;
     * Ec2QueryHandlerTest describes those.
     */
    @Test
    void anRfc4716KeyIsDescribedAsItsOpenSshLine() {
        String name = "public-key-" + UUID.randomUUID();
        String openSsh = Ec2KeyMaterial.generateRsa().openSshPublicKey();
        byte[] material = utf8("---- BEGIN SSH2 PUBLIC KEY ----\nComment: \"" + name + "\"\n" + openSsh.split(" ")[1]
                + "\n---- END SSH2 PUBLIC KEY ----\n");
        describeImported(name, material, response -> response
                .body("DescribeKeyPairsResponse.keySet.item.keyName", equalTo(name))
                .body("DescribeKeyPairsResponse.keySet.item.publicKey", equalTo(openSsh + " " + name)));
    }

    /**
     * IncludePublicKey is a boolean, read as true or false in any case, as the EC2 handler reads its
     * other strictly parsed booleans; a value that is neither is refused rather than read as false.
     */
    @ParameterizedTest
    @CsvSource({"true, 1", "TRUE, 1", "True, 1", "false, 0", "FALSE, 0"})
    void includePublicKeyIsReadAsTrueOrFalseInAnyCase(String include, int publicKeys) {
        describeWithIncludePublicKey(include, response -> response.statusCode(200)
                .body("DescribeKeyPairsResponse.keySet.item.publicKey.size()", equalTo(publicKeys)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid", "yes", "1", "truee"})
    void anIncludePublicKeyThatIsNeitherTrueNorFalseIsRefused(String include) {
        describeWithIncludePublicKey(include, response -> response.statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("IncludePublicKey is not a valid boolean.")));
    }

    /**
     * Imports the material under the given name, describes the key pair with IncludePublicKey=true
     * and hands the response to the check, then deletes the key pair whatever the check found.
     */
    private static void describeImported(String name, byte[] material, Consumer<ValidatableResponse> check) {
        String id = importKey(name, material);
        try {
            check.accept(given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", "true")
                    .post("/").then().statusCode(200)
                    .body("DescribeKeyPairsResponse.keySet.item.keyPairId", equalTo(id)));
        } finally {
            deleteKeyPair(id);
        }
    }

    /**
     * Imports an ed25519 key, describes it by id with this IncludePublicKey value, whatever the
     * answer, and hands the response to the check, then deletes the key pair.
     */
    private static void describeWithIncludePublicKey(String includePublicKey, Consumer<ValidatableResponse> check) {
        String id = importKey("public-key-" + UUID.randomUUID(), utf8("ssh-ed25519 " + ED25519_BLOB));
        try {
            check.accept(given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                    .formParam("KeyPairId.1", id).formParam("IncludePublicKey", includePublicKey)
                    .post("/").then());
        } finally {
            deleteKeyPair(id);
        }
    }

    private static String importKey(String name, byte[] material) {
        return given().header("Authorization", AUTH)
                .formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(material))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
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
