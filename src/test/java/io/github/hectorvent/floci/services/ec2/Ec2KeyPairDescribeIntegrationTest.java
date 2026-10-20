package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * DescribeKeyPairs reports each key pair's keyType and createTime, and Filter.N narrows the result
 * by the filters AWS documents and by key-type.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeKeyPairs.html">DescribeKeyPairs</a>
 */
@QuarkusTest
class Ec2KeyPairDescribeIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261020/us-east-1/ec2/aws4_request";

    private static final String ED25519_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAII0fBPBUZHaEOBc2mySfmI5btu4mkvFfNRujmF7RH2fj";

    @Test
    void createdAndImportedKeyPairsAreDescribedWithTheirTypeAndCreateTime() {
        String prefix = "described-" + UUID.randomUUID();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String created = create(prefix + "-created");
        String importedRsa = importKey(prefix + "-rsa", Ec2KeyMaterial.generateRsa().openSshPublicKey());
        String importedEd25519 = importKey(prefix + "-ed25519", ED25519_KEY + " someone@example.com");
        Instant after = Instant.now();
        try {
            for (String[] expected : new String[][] {{created, "rsa"}, {importedRsa, "rsa"}, {importedEd25519, "ed25519"}}) {
                XmlPath item = given().header("Authorization", AUTH)
                        .formParam("Action", "DescribeKeyPairs").formParam("KeyPairId.1", expected[0])
                        .post("/").then().statusCode(200)
                        .body("DescribeKeyPairsResponse.keySet.item.keyType", equalTo(expected[1]))
                        .extract().xmlPath();
                Instant createTime = Instant.parse(item.getString("DescribeKeyPairsResponse.keySet.item.createTime"));
                assertFalse(createTime.isBefore(before), createTime + " is before " + before);
                assertFalse(createTime.isAfter(after), createTime + " is after " + after);
            }
        } finally {
            delete(created);
            delete(importedRsa);
            delete(importedEd25519);
        }
    }

    /**
     * Every filter name DescribeKeyPairs documents, plus key-type, with wildcards; the values of
     * one filter are ORed, separate filters ANDed. The key pairs share a prefix so the other key
     * pairs this application holds stay out of the answers.
     */
    @Test
    void filtersNarrowTheKeyPairsDescribed() {
        String prefix = "filtered-" + UUID.randomUUID();
        String alpha = create(prefix + "-alpha");
        String beta = importKey(prefix + "-beta", ED25519_KEY);
        String gamma = importKey(prefix + "-gamma", Ec2KeyMaterial.generateRsa().openSshPublicKey());
        try {
            given().header("Authorization", AUTH).formParam("Action", "CreateTags")
                    .formParam("ResourceId.1", beta).formParam("Tag.1.Key", "Team").formParam("Tag.1.Value", "blue")
                    .post("/").then().statusCode(200);
            String gammaFingerprint = given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeKeyPairs").formParam("KeyPairId.1", gamma)
                    .post("/").then().statusCode(200)
                    .extract().path("DescribeKeyPairsResponse.keySet.item.keyFingerprint");

            assertEquals(List.of("alpha", "beta", "gamma"), described(prefix));
            assertEquals(List.of("beta"), described(prefix, "key-pair-id", beta));
            assertEquals(List.of("alpha", "gamma"), described(prefix, "key-name", prefix + "-alpha", prefix + "-gamma"));
            assertEquals(List.of("beta"), described(prefix, "key-name", prefix + "-b?ta"));
            assertEquals(List.of("gamma"), described(prefix, "fingerprint", gammaFingerprint));
            assertEquals(List.of("alpha", "gamma"), described(prefix, "key-type", "rsa"));
            assertEquals(List.of("beta"), described(prefix, "key-type", "ed25519"));
            assertEquals(List.of("beta"), described(prefix, "tag-key", "Team"));
            assertEquals(List.of("beta"), described(prefix, "tag:Team", "blue"));
            assertEquals(List.of(), described(prefix, "tag:Team", "red"));
        } finally {
            delete(alpha);
            delete(beta);
            delete(gamma);
        }
    }

    /** A filter name DescribeKeyPairs does not know is an error, not a filter that matches everything. */
    @Test
    void anUnknownFilterIsRefused() {
        given().header("Authorization", AUTH).formParam("Action", "DescribeKeyPairs")
                .formParam("Filter.1.Name", "owner-id").formParam("Filter.1.Value.1", "000000000000")
                .post("/").then().statusCode(400)
                .body("Response.Errors.Error.Code", equalTo("InvalidParameterValue"))
                .body("Response.Errors.Error.Message", equalTo("The filter 'owner-id' is invalid"));
    }

    /**
     * The key pairs DescribeKeyPairs returns for the given filter, a name and its values, ANDed with
     * a key-name filter of {@code prefix-*}: their names without the prefix, sorted. Without that
     * second filter every key pair this application holds would come back. A key-name filter under
     * test is sent alone, with values that carry the prefix, since a request names a filter once.
     */
    private static List<String> described(String prefix, String... filter) {
        RequestSpecification request = given().header("Authorization", AUTH)
                .formParam("Action", "DescribeKeyPairs");
        int next = 1;
        if (filter.length == 0 || !"key-name".equals(filter[0])) {
            request.formParam("Filter.1.Name", "key-name").formParam("Filter.1.Value.1", prefix + "-*");
            next = 2;
        }
        if (filter.length > 0) {
            request.formParam("Filter." + next + ".Name", filter[0]);
            for (int i = 1; i < filter.length; i++) {
                request.formParam("Filter." + next + ".Value." + i, filter[i]);
            }
        }
        List<String> names = new ArrayList<>();
        for (String name : request.post("/").then().statusCode(200).extract().xmlPath()
                .getList("DescribeKeyPairsResponse.keySet.item.keyName", String.class)) {
            names.add(name.startsWith(prefix + "-") ? name.substring(prefix.length() + 1) : name);
        }
        names.sort(null);
        return names;
    }

    private static String create(String name) {
        return given().header("Authorization", AUTH).formParam("Action", "CreateKeyPair").formParam("KeyName", name)
                .post("/").then().statusCode(200).extract().path("CreateKeyPairResponse.keyPairId");
    }

    private static String importKey(String name, String publicKey) {
        return given().header("Authorization", AUTH).formParam("Action", "ImportKeyPair").formParam("KeyName", name)
                .formParam("PublicKeyMaterial",
                        Base64.getEncoder().encodeToString(publicKey.getBytes(StandardCharsets.UTF_8)))
                .post("/").then().statusCode(200).extract().path("ImportKeyPairResponse.keyPairId");
    }

    private static void delete(String id) {
        given().header("Authorization", AUTH).formParam("Action", "DeleteKeyPair").formParam("KeyPairId", id)
                .post("/").then().statusCode(200);
    }
}
