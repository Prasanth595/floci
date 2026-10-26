package io.github.hectorvent.floci.services.apigatewayv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class HttpApiAuthorizerIdentitySourceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUTHORIZER = "arn:aws:lambda:us-east-1:000000000000:function:identity-source-authorizer";
    private static final String BACKEND = "arn:aws:lambda:us-east-1:000000000000:function:identity-source-backend";

    @InjectMock
    LambdaService lambdaService;

    private final AtomicReference<JsonNode> authorizerEvent = new AtomicReference<>();
    private String apiId;
    private String stageName = "test";
    private boolean allowed = true;

    @BeforeEach
    void stubLambdaBoundary() {
        when(lambdaService.invokeTarget(eq("us-east-1"), eq(AUTHORIZER), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    authorizerEvent.set(MAPPER.readTree((byte[]) invocation.getArgument(2)));
                    return result("{\"isAuthorized\":" + allowed + "}");
                });
        when(lambdaService.invokeTarget(eq("us-east-1"), eq(BACKEND), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenReturn(result("{\"statusCode\":200,\"body\":\"allowed\"}"));
    }

    @AfterEach
    void deleteApi() {
        if (apiId != null) {
            given().delete("/v2/apis/" + apiId).then().statusCode(204);
        }
    }

    @Test
    void headerIdentityIsAnArrayAndKeepsTheBearerPrefix() {
        configureApi(List.of("$request.header.authorization"));
        given().header("Authorization", "Bearer opaque-token").get(path()).then().statusCode(200);
        assertIdentities(List.of("Bearer opaque-token"));
    }

    @Test
    void multipleSourcesKeepConfiguredOrderAndResolveContextAndStageValues() {
        configureApi(List.of("$context.routeKey", "$stageVariables.tenant", "$request.querystring.Token",
                "$request.header.X-Identity", "$context.stage", "$context.requestId"));
        given().header("x-identity", "header-value").queryParam("Token", "query-value")
                .get(path()).then().statusCode(200);
        JsonNode event = authorizerEvent.get();
        assertNotNull(event);
        assertIdentities(List.of("GET /items", "stage-tenant", "query-value", "header-value", "test",
                event.path("requestContext").path("requestId").asText()));
    }

    @Test
    void contextAliasesUseTheValuesFromThisEvent() {
        configureApi(List.of("$context.httpMethod", "$context.protocol", "$context.identity.sourceIp",
                "$context.identity.userAgent", "$context.requestTime", "$context.requestTimeEpoch", "$context.extendedRequestId"));
        given().header("User-Agent", "identity-client").get(path()).then().statusCode(200);
        JsonNode context = authorizerEvent.get().path("requestContext");
        assertIdentities(List.of("GET", "HTTP/1.1", context.path("http").path("sourceIp").asText(),
                "identity-client", context.path("time").asText(), context.path("timeEpoch").asText(),
                context.path("requestId").asText()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "$default"})
    void contextPathIncludesOnlyANamedStage(String stage) {
        stageName = stage;
        configureApi(List.of("$context.path"));
        given().get(path()).then().statusCode(200);
        assertIdentities(List.of("$default".equals(stage) ? "/items" : "/test/items"));
    }

    @Test
    void repeatedRequestValuesAreJoinedLikeTheEventFields() {
        configureApi(List.of("$request.querystring.Token", "$request.header.X-Identity", "$request.querystring.Token"));
        given().header("X-Identity", "first", "second").queryParam("Token", "one", "two")
                .get(path()).then().statusCode(200);
        assertIdentities(List.of("one,two", "first,second", "one,two"));
    }

    @Test
    void noConfiguredSourcesProducesAnEmptyArray() {
        configureApi(List.of());
        given().get(path()).then().statusCode(200);
        assertIdentities(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"$request.header.Missing", "$request.querystring.Missing", "$request.querystring.Token",
            "$stageVariables.missing", "$context.missing", "$context.http"})
    void missingOrWrongCaseRequestSourceDoesNotInvokeLambda(String source) {
        configureApi(List.of(source));
        given().queryParam("token", "wrong-case").get(path()).then().statusCode(401);
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(AUTHORIZER), any(byte[].class), eq(InvocationType.RequestResponse));
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(BACKEND), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void emptyConfiguredValueDoesNotInvokeLambda() {
        configureApi(List.of("$request.querystring.Token"));
        given().queryParam("Token", "").get(path()).then().statusCode(401);
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(AUTHORIZER), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    @Test
    void identityValuesDoNotBypassADenyResponse() {
        allowed = false;
        configureApi(List.of("$request.header.Authorization"));
        given().header("Authorization", "Bearer denied-token").get(path()).then().statusCode(403);
        assertIdentities(List.of("Bearer denied-token"));
        verify(lambdaService, never()).invokeTarget(eq("us-east-1"), eq(BACKEND), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    private void assertIdentities(List<String> expected) {
        assertNotNull(authorizerEvent.get());
        assertEquals(MAPPER.valueToTree(expected), authorizerEvent.get().get("identitySource"));
    }

    private String path() {
        return "/execute-api/" + apiId + "/" + stageName + "/items";
    }

    private void configureApi(List<String> sources) {
        apiId = given().contentType(ContentType.JSON).body(Map.of("name", "identity-sources", "protocolType", "HTTP"))
                .post("/v2/apis").then().statusCode(201).extract().path("apiId");
        String apiPath = "/v2/apis/" + apiId;
        given().contentType(ContentType.JSON).body(Map.of("stageName", stageName, "stageVariables", Map.of("tenant", "stage-tenant")))
                .post(apiPath + "/stages").then().statusCode(201);
        String authorizerId = given().contentType(ContentType.JSON)
                .body(Map.of("name", "identity-source", "authorizerType", "REQUEST", "authorizerPayloadFormatVersion", "2.0",
                        "authorizerUri", AUTHORIZER, "enableSimpleResponses", true, "identitySource", sources))
                .post(apiPath + "/authorizers").then().statusCode(201).extract().path("authorizerId");
        String integrationId = given().contentType(ContentType.JSON)
                .body(Map.of("integrationType", "AWS_PROXY", "payloadFormatVersion", "2.0", "integrationUri", BACKEND))
                .post(apiPath + "/integrations").then().statusCode(201).extract().path("integrationId");
        given().contentType(ContentType.JSON)
                .body(Map.of("routeKey", "GET /items", "authorizationType", "CUSTOM", "authorizerId", authorizerId,
                        "target", "integrations/" + integrationId))
                .post(apiPath + "/routes").then().statusCode(201);
    }

    private InvokeResult result(String payload) {
        return new InvokeResult(200, null, payload.getBytes(StandardCharsets.UTF_8), null, "request");
    }
}
