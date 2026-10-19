package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.apigatewayv2.ApiGatewayV2Client;
import software.amazon.awssdk.services.apigatewayv2.model.IntegrationType;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Runtime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an HTTP API answers the Lambda proxy results it cannot read, in payload formats 1.0 and 2.0.
 * Measured on AWS (eu-west-1, 2026-10-19): a function that raises, a 1.0 result without a
 * statusCode, and a statusCode that is not a whole number from 100 to 599 are answered 500 with
 * {"message":"Internal Server Error"}; a statusCode given as text that spells one is taken; format
 * 2.0 takes a result without a statusCode as a 200 whose body is the result itself.
 */
@DisplayName("API Gateway V2 Lambda proxy responses")
class ApiGatewayV2LambdaProxyResponseTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/lambda-role";
    private static final String ERROR = "{\"message\":\"Internal Server Error\"}";
    private static final String STAGE = "test";

    private static ApiGatewayV2Client apiGatewayV2;
    private static LambdaClient lambda;
    private static HttpClient http;
    private static String functionName;
    private static String apiId;

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(TestFixtures.isLambdaDispatchAvailable(), "Lambda dispatch is unavailable here");
        apiGatewayV2 = TestFixtures.apiGatewayV2Client();
        lambda = TestFixtures.lambdaClient();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        functionName = TestFixtures.uniqueName("http-api-proxy-fn");

        String functionArn = lambda.createFunction(request -> request
                .functionName(functionName)
                .runtime(Runtime.NODEJS20_X)
                .role(ROLE)
                .handler("index.handler")
                .timeout(30)
                .code(code -> code.zipFile(SdkBytes.fromByteArray(LambdaUtils.proxyResponseZip())))).functionArn();
        apiId = apiGatewayV2.createApi(request -> request
                .name(TestFixtures.uniqueName("http-api-proxy"))
                .protocolType("HTTP")).apiId();
        for (String format : new String[] {"1.0", "2.0"}) {
            String integrationId = apiGatewayV2.createIntegration(request -> request
                    .apiId(apiId)
                    .integrationType(IntegrationType.AWS_PROXY)
                    .integrationUri(functionArn)
                    .payloadFormatVersion(format)).integrationId();
            apiGatewayV2.createRoute(request -> request
                    .apiId(apiId)
                    .routeKey("GET /v" + format.charAt(0) + "/{mode}")
                    .target("integrations/" + integrationId));
        }
        apiGatewayV2.createStage(request -> request.apiId(apiId).stageName(STAGE).autoDeploy(true));
    }

    @AfterAll
    static void cleanup() {
        if (apiGatewayV2 != null) {
            if (apiId != null) {
                apiGatewayV2.deleteApi(request -> request.apiId(apiId));
            }
            apiGatewayV2.close();
        }
        if (lambda != null) {
            lambda.deleteFunction(request -> request.functionName(functionName));
            lambda.close();
        }
    }

    @ParameterizedTest(name = "{1} in payload {0}")
    @CsvSource(delimiter = '|', value = {
        "1.0|raise|500|" + ERROR,
        "2.0|raise|500|" + ERROR,
        "1.0|nostatus|500|" + ERROR,
        "1.0|badstatus|500|" + ERROR,
        "2.0|badstatus|500|" + ERROR,
        "1.0|highstatus|500|" + ERROR,
        "2.0|highstatus|500|" + ERROR,
        "1.0|textstatus|201|x",
        "2.0|textstatus|201|x",
        "2.0|nostatus|200|{\"body\":\"x\",\"headers\":{\"x-probe\":\"1\"}}"
    })
    void aResultIsAnsweredAsAwsReadsIt(String format, String mode, int status, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                        .uri(URI.create(TestFixtures.endpoint() + "/execute-api/" + apiId + "/" + STAGE + "/v"
                                + format.charAt(0) + "/" + mode))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).isEqualTo(body);
    }
}
