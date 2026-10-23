package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CloudWatch JSON 1.0 requests name their operation with either target prefix: the
 * {@code GraniteServiceVersion20100801.} prefix of the AWS SDKs, or the
 * {@code CloudWatchVersion20130116.} prefix of the CloudWatch console's API. Both reach the same
 * handler and the same stored metrics and alarms. The second prefix is JSON only, so CBOR and
 * Smithy RPC v2 requests that name it stay unknown.
 */
@QuarkusTest
class CloudWatchJsonTargetIntegrationTest {

    private static final String CURRENT_TARGET = "GraniteServiceVersion20100801.";
    private static final String LEGACY_TARGET = "CloudWatchVersion20130116.";
    private static final String AUTHORIZATION =
            "AWS4-HMAC-SHA256 Credential=test/20261022/us-east-1/monitoring/aws4_request";

    @BeforeAll
    static void configureAwsJson() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {CURRENT_TARGET, LEGACY_TARGET})
    void listMetricsThroughEitherTargetSeesTheSameMetrics(String prefix) {
        String namespace = "json-target-" + UUID.randomUUID();
        json(CURRENT_TARGET, "PutMetricData", """
                {"Namespace":"%s","MetricData":[{"MetricName":"Requests","Value":1}]}
                """.formatted(namespace)).then().statusCode(200);

        json(prefix, "ListMetrics", "{\"Namespace\":\"" + namespace + "\"}")
                .then().statusCode(200).contentType(containsString("json"))
                .body("Metrics.MetricName", contains("Requests"));
    }

    @ParameterizedTest
    @ValueSource(strings = {CURRENT_TARGET, LEGACY_TARGET})
    void describeAlarmsThroughEitherTargetSeesTheSameAlarm(String prefix) {
        String alarm = "json-target-" + UUID.randomUUID();
        json(CURRENT_TARGET, "PutMetricAlarm", """
                {"AlarmName":"%s","Namespace":"Example/JsonTarget","MetricName":"Requests",
                 "Statistic":"Sum","Period":60,"EvaluationPeriods":1,"Threshold":1,
                 "ComparisonOperator":"GreaterThanThreshold"}
                """.formatted(alarm)).then().statusCode(200);
        try {
            json(prefix, "DescribeAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}")
                    .then().statusCode(200).contentType(containsString("json"))
                    .body("MetricAlarms.AlarmName", contains(alarm));
        } finally {
            json(CURRENT_TARGET, "DeleteAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}")
                    .then().statusCode(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CloudWatchVersion20100801.", "CloudWatchVersion19000101.", "CloudWatchVersion."})
    void otherCloudWatchVersionsStayUnknown(String prefix) {
        json(prefix, "ListMetrics", "{}").then().statusCode(404)
                .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void cborRequestsCannotNameTheLegacyTarget() throws Exception {
        byte[] response = given().contentType("application/x-amz-cbor-1.1")
                .header("Authorization", AUTHORIZATION)
                .header("X-Amz-Target", LEGACY_TARGET + "ListMetrics")
                .body(new ObjectMapper(new CBORFactory()).writeValueAsBytes(Map.of()))
                .post("/").then().statusCode(404).contentType("application/x-amz-cbor-1.1")
                .extract().asByteArray();

        assertEquals("UnknownOperationException",
                new ObjectMapper(new CBORFactory()).readTree(response).path("__type").asText());
    }

    @Test
    void smithyRpcV2CannotNameTheLegacyService() throws Exception {
        given().contentType("application/cbor").header("smithy-protocol", "rpc-v2-cbor")
                .header("Authorization", AUTHORIZATION)
                .body(new ObjectMapper(new CBORFactory()).writeValueAsBytes(Map.of()))
                .post("/service/CloudWatchVersion20130116/operation/ListMetrics")
                .then().statusCode(404);
    }

    private static Response json(String prefix, String operation, String body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("Authorization", AUTHORIZATION)
                .header("X-Amz-Target", prefix + operation)
                .body(body).post("/");
    }
}
