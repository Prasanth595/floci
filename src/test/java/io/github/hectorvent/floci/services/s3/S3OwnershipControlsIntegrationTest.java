package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3OwnershipControlsIntegrationTest {

    private static final String BUCKET = "ownership-controls-int-test";
    private static final String OWNERSHIP_CONTROLS_XML = """
            <OwnershipControls xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Rule>
                    <ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>
                </Rule>
            </OwnershipControls>
            """;

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void getOwnershipControlsBeforePutReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(404)
            .body(containsString("OwnershipControlsNotFoundError"));
    }

    @Test
    @Order(3)
    void putOwnershipControlsReturns200() {
        given()
            .body(OWNERSHIP_CONTROLS_XML)
        .when()
            .put("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void getOwnershipControlsReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(200)
            .body(containsString("<OwnershipControls"))
            .body(containsString("<ObjectOwnership>BucketOwnerPreferred</ObjectOwnership>"));
    }

    @Test
    @Order(5)
    void enforcedOwnershipRejectsAclWritesAndDisallowedUploadAcls() {
        given()
            .body("object body")
        .when()
            .put("/" + BUCKET + "/owned-object")
        .then()
            .statusCode(200);

        given()
            .body(OWNERSHIP_CONTROLS_XML.replace("BucketOwnerPreferred", "BucketOwnerEnforced"))
        .when()
            .put("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-acl", "public-read")
        .when()
            .put("/" + BUCKET + "?acl")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .header("x-amz-acl", "public-read")
        .when()
            .put("/" + BUCKET + "/owned-object?acl")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .header("x-amz-acl", "public-read")
            .body("new object")
        .when()
            .put("/" + BUCKET + "/rejected-object")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .header("x-amz-acl", "public-read")
        .when()
            .post("/" + BUCKET + "/rejected-multipart?uploads")
        .then()
            .statusCode(400)
            .body(containsString("AccessControlListNotSupported"));

        given()
            .body("new object")
        .when()
            .put("/" + BUCKET + "/no-acl-object")
        .then()
            .statusCode(200);

        given()
            .header("x-amz-acl", "bucket-owner-full-control")
            .body("new object")
        .when()
            .put("/" + BUCKET + "/accepted-object")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(6)
    void deleteOwnershipControlsReturns204() {
        given()
        .when()
            .delete("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(7)
    void getOwnershipControlsAfterDeleteReturns404() {
        given()
        .when()
            .get("/" + BUCKET + "?ownershipControls")
        .then()
            .statusCode(404)
            .body(containsString("OwnershipControlsNotFoundError"));
    }
}
