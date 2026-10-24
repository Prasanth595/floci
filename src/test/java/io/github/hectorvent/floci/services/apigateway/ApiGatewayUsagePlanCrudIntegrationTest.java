package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestHTTPEndpoint(ApiGatewayController.class)
class ApiGatewayUsagePlanCrudIntegrationTest {

    @Test
    void testCreateAndRetrieveUsagePlan() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"before\",\"description\":\"old\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":["
                        + "{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"after\"},"
                        + "{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"new\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("name", equalTo("after"))
                .body("description", equalTo("new"));

        given()
                .get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("id", equalTo(usagePlanId))
                .body("name", equalTo("after"))
                .body("description", equalTo("new"));
    }

    @Test
    void testUnsupportedPatchPathIsRejected() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"reject-path\",\"description\":\"old\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        given()
                .contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/nope\",\"value\":\"x\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(400);
    }

    @Test
    void aPlanReportsItsStagesFromCreateAndBothReads() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"staged\",\"apiStages\":[{\"apiId\":\"a1\",\"stage\":\"v1\"}]}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("apiStages", hasSize(1))
                .body("apiStages[0].apiId", equalTo("a1"))
                .body("apiStages[0].stage", equalTo("v1"))
                .body("apiStages[0].throttle", nullValue())
                .extract()
                .path("id");

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages[0].apiId", equalTo("a1"))
                .body("apiStages[0].stage", equalTo("v1"));

        given().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.find { it.id == '" + usagePlanId + "' }.apiStages[0].stage", equalTo("v1"));
    }

    @Test
    void aPlanWithoutStagesReportsAnEmptyListAndNoLimits() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"bare\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("apiStages", hasSize(0))
                .body("throttle", nullValue())
                .body("quota", nullValue())
                .extract()
                .path("id");

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages", hasSize(0));
    }

    @Test
    void aStageWithAMethodThrottleIsMatchedByApiAndStage() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"stage-remove\",\"throttle\":{\"burstLimit\":20,\"rateLimit\":10},"
                        + "\"apiStages\":[{\"apiId\":\"a1\",\"stage\":\"v1\","
                        + "\"throttle\":{\"/pets/GET\":{\"burstLimit\":10,\"rateLimit\":5}}},"
                        + "{\"apiId\":\"a1\",\"stage\":\"v2\"}]}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("apiStages[0].throttle['/pets/GET'].burstLimit", equalTo(10))
                .extract()
                .path("id");

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"add\",\"path\":\"/apiStages\",\"value\":\"a1:v1\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages", hasSize(2))
                .body("apiStages[0].throttle['/pets/GET'].burstLimit", equalTo(10));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"remove\",\"path\":\"/apiStages\",\"value\":\"a1:v1\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages", hasSize(1))
                .body("apiStages[0].stage", equalTo("v2"));

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("apiStages", hasSize(1))
                .body("apiStages[0].stage", equalTo("v2"))
                .body("throttle.burstLimit", equalTo(20));
    }

    @Test
    void anUnknownQuotaPeriodOnCreateIsAValidationException() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"bad-period\",\"quota\":{\"limit\":1,\"period\":\"day\"}}")
                .post("/usageplans")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    // Measured on API Gateway: GetUsagePlan and UpdateUsagePlan carry "tags": {}, CreateUsagePlan and
    // the GetUsagePlans items leave an empty map out.
    @Test
    void getAndUpdateReportEmptyTagsWhileCreateAndListLeaveThemOut() {
        String usagePlanId = given()
                .contentType(ContentType.JSON)
                .body("{\"name\":\"untagged\"}")
                .post("/usageplans")
                .then()
                .statusCode(201)
                .body("$", not(hasKey("tags")))
                .extract()
                .path("id");

        given().get("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"untagged-renamed\"}]}")
                .patch("/usageplans/{usagePlanId}", usagePlanId)
                .then()
                .statusCode(200)
                .body("tags", anEmptyMap());

        given().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.find { it.id == '" + usagePlanId + "' }", not(hasKey("tags")));
    }

    @Test
    void anUnknownPlanIdIsNotFoundWithApiGatewaysMessage() {
        given().get("/usageplans/{usagePlanId}", "no-such-plan")
                .then()
                .statusCode(404)
                .body("__type", equalTo("NotFoundException"))
                .body("message", equalTo("Invalid Usage Plan ID specified"));
    }

    @Test
    void aStageWithoutAnApiIdIsRefusedAndNoPlanIsStored() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"stage-without-api\",\"apiStages\":[{\"stage\":\"v1\"}]}")
                .post("/usageplans")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid API Stage specified"));

        given().get("/usageplans")
                .then()
                .statusCode(200)
                .body("item.findAll { it.name == 'stage-without-api' }", hasSize(0));
    }

    @Test
    void aWrongJsonTypeOnCreateIsASerializationError() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"wrong-type\",\"quota\":\"invalid\"}")
                .post("/usageplans")
                .then()
                .statusCode(400)
                .body("__type", equalTo("SerializationException"))
                .body("message", equalTo("Expected null"));
    }
}
