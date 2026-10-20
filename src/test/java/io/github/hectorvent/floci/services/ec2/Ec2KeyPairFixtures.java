package io.github.hectorvent.floci.services.ec2;

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static io.restassured.RestAssured.given;

/** The key pair calls and the ED25519 key shared by the EC2 key pair tests. */
final class Ec2KeyPairFixtures {

    static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/ec2/aws4_request";

    /**
     * A throwaway ED25519 public key as an OpenSSH line without a comment. Ec2KeyMaterialTest pins
     * its fingerprint to what {@code ssh-keygen -l} reports.
     */
    static final String ED25519_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj";

    /** The base64 blob of {@link #ED25519_KEY}. */
    static final String ED25519_BLOB = ED25519_KEY.split(" ")[1];

    private Ec2KeyPairFixtures() {
    }

    /** A request for the given EC2 action, signed for us-east-1. */
    static RequestSpecification ec2(String action) {
        return given().header("Authorization", AUTH).formParam("Action", action);
    }

    /** CreateKeyPair with this name, whatever the answer. */
    static ValidatableResponse createKeyPair(String name) {
        return ec2("CreateKeyPair").formParam("KeyName", name).post("/").then();
    }

    /** ImportKeyPair with this name and material, the material base64-encoded as the API takes it. */
    static ValidatableResponse importKeyPair(String name, byte[] material) {
        return ec2("ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial", Base64.getEncoder().encodeToString(material))
                .post("/").then();
    }

    /** ImportKeyPair with this name and the UTF-8 bytes of this material. */
    static ValidatableResponse importKeyPair(String name, String material) {
        return importKeyPair(name, material.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a key pair and returns its id. */
    static String createdKeyPairId(String name) {
        return createKeyPair(name).statusCode(200).extract().path("CreateKeyPairResponse.keyPairId");
    }

    /** Imports the material and returns the new key pair's id. */
    static String importedKeyPairId(String name, byte[] material) {
        return importKeyPair(name, material).statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
    }

    /** Imports the UTF-8 bytes of the material and returns the new key pair's id. */
    static String importedKeyPairId(String name, String material) {
        return importedKeyPairId(name, material.getBytes(StandardCharsets.UTF_8));
    }

    static void deleteKeyPair(String id) {
        ec2("DeleteKeyPair").formParam("KeyPairId", id).post("/").then().statusCode(200);
    }
}
