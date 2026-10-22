package io.github.hectorvent.floci.services.apigatewayv2;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * End to end check of the Content-Type an HTTP API answers a Lambda proxy integration with.
 * Measured on AWS (eu-west-1, 2026-10-19): a result that carries a body and no Content-Type
 * header of its own is answered {@code text/plain; charset=utf-8}, in both payload formats. A
 * result that names a Content-Type keeps it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpApiLambdaProxyContentTypeIntegrationTest {

    private static final String PLAIN_FUNCTION = "httpv2-ct-plain-fn";
    private static final String PLAIN_V1_FUNCTION = "httpv2-ct-plain-v1-fn";
    private static final String JSON_FUNCTION = "httpv2-ct-json-fn";

    private static String apiId;

    @Test
    @Order(1)
    void setupLambdasAndHttpApi() throws Exception {
        apiId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"name":"content-type-test","protocolType":"HTTP"}
                        """)
                .when().post("/v2/apis")
                .then()
                .statusCode(201)
                .body("apiId", notNullValue())
                .extract().path("apiId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"stageName":"test"}
                        """)
                .when().post("/v2/apis/" + apiId + "/stages")
                .then().statusCode(201);

        createProxyRoute(PLAIN_FUNCTION, "/plain", "2.0",
                "exports.handler = async () => ({ statusCode: 200, body: 'hello' });");
        createProxyRoute(PLAIN_V1_FUNCTION, "/plain-v1", "1.0",
                "exports.handler = async () => ({ statusCode: 200, body: 'hello' });");
        createProxyRoute(JSON_FUNCTION, "/json", "2.0",
                "exports.handler = async () => ({ statusCode: 200, headers: { 'Content-Type': 'application/json' }, body: '{\"a\":1}' });");
    }

    private static void createProxyRoute(String functionName, String path, String payloadFormatVersion,
                                         String handlerCode) throws Exception {
        String zip = WebSocketTestSupport.createLambdaZip(handlerCode);

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"FunctionName":"%s","Runtime":"nodejs20.x","Role":"arn:aws:iam::000000000000:role/lambda-role","Handler":"index.handler","Timeout":30,"Code":{"ZipFile":"%s"}}
                        """.formatted(functionName, zip))
                .when().post("/2015-03-31/functions")
                .then().statusCode(201);

        String integrationId = given()
                .contentType(ContentType.JSON)
                .body("""
                        {"integrationType":"AWS_PROXY","integrationUri":"arn:aws:lambda:us-east-1:000000000000:function:%s/invocations","integrationMethod":"POST","payloadFormatVersion":"%s"}
                        """.formatted(functionName, payloadFormatVersion))
                .when().post("/v2/apis/" + apiId + "/integrations")
                .then()
                .statusCode(201)
                .extract().path("integrationId");

        given()
                .contentType(ContentType.JSON)
                .body("""
                        {"routeKey":"GET %s","target":"integrations/%s"}
                        """.formatted(path, integrationId))
                .when().post("/v2/apis/" + apiId + "/routes")
                .then().statusCode(201);
    }

    @Test
    @Order(10)
    void aResultWithoutAContentTypeIsAnsweredAsTextPlain() {
        given()
                .when().get("/execute-api/" + apiId + "/test/plain")
                .then()
                .statusCode(200)
                .contentType(allOf(startsWith("text/plain"), containsStringIgnoringCase("charset=utf-8")))
                .body(equalTo("hello"));
    }

    @Test
    @Order(11)
    void aResultWithoutAContentTypeIsAnsweredAsTextPlainInPayloadFormatOne() {
        given()
                .when().get("/execute-api/" + apiId + "/test/plain-v1")
                .then()
                .statusCode(200)
                .contentType(allOf(startsWith("text/plain"), containsStringIgnoringCase("charset=utf-8")))
                .body(equalTo("hello"));
    }

    @Test
    @Order(12)
    void aResultThatNamesAContentTypeKeepsIt() {
        given()
                .when().get("/execute-api/" + apiId + "/test/json")
                .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body(equalTo("{\"a\":1}"));
    }

    @Test
    @Order(999)
    void cleanup() throws Exception {
        if (apiId != null) {
            given().when().delete("/v2/apis/" + apiId);
        }
        WebSocketTestSupport.deleteFunctions(PLAIN_FUNCTION, PLAIN_V1_FUNCTION, JSON_FUNCTION);
    }
}
