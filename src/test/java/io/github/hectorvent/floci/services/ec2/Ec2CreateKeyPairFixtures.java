package io.github.hectorvent.floci.services.ec2;

import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil;
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;

import java.io.IOException;
import java.io.StringReader;

import static io.github.hectorvent.floci.testing.Ec2Requests.ec2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The CreateKeyPair calls and the key readers the CreateKeyPair tests share. */
final class Ec2CreateKeyPairFixtures {

    static final String ACCOUNT_ID = "000000000000";
    static final String REGION = "us-east-1";

    private Ec2CreateKeyPairFixtures() {
    }

    /** CreateKeyPair with this name, and the KeyType and KeyFormat when they are not null. */
    static ValidatableResponse createKeyPair(String name, String keyType, String keyFormat) {
        RequestSpecification request = ec2("CreateKeyPair", ACCOUNT_ID, REGION).formParam("KeyName", name);
        if (keyType != null) {
            request.formParam("KeyType", keyType);
        }
        if (keyFormat != null) {
            request.formParam("KeyFormat", keyFormat);
        }
        return request.post("/").then();
    }

    /** Deletes the key pair with this id, and checks DeleteKeyPair answered 200. */
    static void deleteKeyPair(String id) {
        ec2("DeleteKeyPair", ACCOUNT_ID, REGION).formParam("KeyPairId", id).post("/").then().statusCode(200);
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

    /** The first line of key material, which a failure message can show without disclosing the key. */
    static String firstLine(String material) {
        return material.lines().findFirst().orElse("");
    }
}
