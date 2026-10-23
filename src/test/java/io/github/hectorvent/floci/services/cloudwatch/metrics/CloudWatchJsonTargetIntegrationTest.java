package io.github.hectorvent.floci.services.cloudwatch.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CloudWatch JSON 1.0 requests name their operation with either target prefix: the
 * {@code GraniteServiceVersion20100801.} prefix of the AWS SDKs, or the
 * {@code CloudWatchVersion20130116.} prefix of the CloudWatch console's API. The console prefix
 * reaches the same metrics and alarms for the operations whose shapes the two APIs share, and
 * refuses the others and the request members only the console's API defines, which the handler
 * would otherwise ignore. It is JSON only, so CBOR and Smithy RPC v2 requests that name it stay
 * unknown.
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
    void getMetricStatisticsThroughEitherTargetSeesTheSameDatapoints(String prefix) {
        String namespace = "json-target-" + UUID.randomUUID();
        long now = Instant.now().getEpochSecond();
        json(CURRENT_TARGET, "PutMetricData", """
                {"Namespace":"%s","MetricData":[{"MetricName":"Requests","Value":5,"Timestamp":%d}]}
                """.formatted(namespace, now)).then().statusCode(200);

        Response statistics = json(prefix, "GetMetricStatistics", """
                {"Namespace":"%s","MetricName":"Requests","StartTime":%d,"EndTime":%d,
                 "Period":60,"Statistics":["Sum"]}
                """.formatted(namespace, now - 300, now + 300));
        statistics.then().statusCode(200).contentType(containsString("json"));
        assertEquals(5.0, statistics.jsonPath().getDouble("Datapoints[0].Sum"));
    }

    @ParameterizedTest
    @ValueSource(strings = {CURRENT_TARGET, LEGACY_TARGET})
    void describeAlarmsThroughEitherTargetSeesTheSameAlarm(String prefix) {
        String alarm = "json-target-" + UUID.randomUUID();
        putAlarm(alarm);
        try {
            json(prefix, "DescribeAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}")
                    .then().statusCode(200).contentType(containsString("json"))
                    .body("MetricAlarms.AlarmName", contains(alarm));
        } finally {
            json(CURRENT_TARGET, "DeleteAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}")
                    .then().statusCode(200);
        }
    }

    @Test
    void deleteAlarmsThroughTheConsoleTargetRemovesTheAlarm() {
        String alarm = "json-target-" + UUID.randomUUID();
        putAlarm(alarm);
        try {
            json(LEGACY_TARGET, "DeleteAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}")
                    .then().statusCode(200);
            json(CURRENT_TARGET, "DescribeAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}")
                    .then().statusCode(200).body("MetricAlarms", empty());
        } finally {
            json(CURRENT_TARGET, "DeleteAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}");
        }
    }

    @Test
    void consoleGetMetricDataIsRefusedRatherThanAnsweredWithNoResults() {
        json(LEGACY_TARGET, "GetMetricData", """
                {"Defaults":{"Period":60,"Stat":"Sum","Range":{"StartTime":1761091200,"EndTime":1761094800}},
                 "Metrics":[{"Alias":"m1","Namespace":"Example/JsonTarget","MetricName":"Requests"}]}
                """).then().statusCode(400).body("__type", equalTo("UnsupportedOperation"));
    }

    @Test
    void consolePutMetricAlarmIsRefusedAndStoresNothing() {
        String alarm = "json-target-" + UUID.randomUUID();
        json(LEGACY_TARGET, "PutMetricAlarm", """
                {"AlarmName":"%s","EvaluationPeriods":1,"Threshold":1,
                 "ComparisonOperator":"GreaterThanThreshold",
                 "Metrics":[{"Alias":"m1","Namespace":"Example/JsonTarget","MetricName":"Requests",
                   "Stat":"Sum","Period":60,"ReturnData":true}]}
                """.formatted(alarm)).then().statusCode(400).body("__type", equalTo("UnsupportedOperation"));

        json(CURRENT_TARGET, "DescribeAlarms", "{\"AlarmNames\":[\"" + alarm + "\"]}")
                .then().statusCode(200).body("MetricAlarms", empty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            PutMetricData       | {"Namespace":"Example/JsonTarget","MetricData":[{"MetricName":"Requests","Value":1}]}
            SetAlarmState       | {"AlarmName":"json-target-none","StateValue":"OK","StateReason":"test"}
            PutDashboard        | {"DashboardName":"json-target-none","DashboardBody":"{}"}
            ListTagsForResource | {"ResourceARN":"arn:aws:cloudwatch:us-east-1:000000000000:alarm:json-target-none"}
            ListMetricStreams   | {}
            getMetricData       | {}
            """)
    void operationsTheConsoleTargetDoesNotShareAreRefused(String operation, String body) {
        json(LEGACY_TARGET, operation, body).then().statusCode(400)
                .body("__type", equalTo("UnsupportedOperation"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ListMetrics    | {"CountOnlyRequest":true}
            ListMetrics    | {"SegmentId":0,"TotalSegments":2}
            DescribeAlarms | {"ExcludeAutoscalingAlarms":true}
            DescribeAlarms | {"ExcludeManagedAlarms":true}
            """)
    void consoleOnlyMembersAreRefused(String operation, String body) {
        json(LEGACY_TARGET, operation, body).then().statusCode(400)
                .body("__type", equalTo("UnsupportedOperation"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ListMetrics    | {"CountOnlyRequest":false}
            DescribeAlarms | {"ExcludeAutoscalingAlarms":false,"ExcludeManagedAlarms":null}
            """)
    void consoleOnlyMembersLeftAtTheirDefaultsAreServed(String operation, String body) {
        json(LEGACY_TARGET, operation, body).then().statusCode(200);
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

    private static void putAlarm(String alarm) {
        json(CURRENT_TARGET, "PutMetricAlarm", """
                {"AlarmName":"%s","Namespace":"Example/JsonTarget","MetricName":"Requests",
                 "Statistic":"Sum","Period":60,"EvaluationPeriods":1,"Threshold":1,
                 "ComparisonOperator":"GreaterThanThreshold"}
                """.formatted(alarm)).then().statusCode(200);
    }

    private static Response json(String prefix, String operation, String body) {
        return given().contentType("application/x-amz-json-1.0")
                .header("Authorization", AUTHORIZATION)
                .header("X-Amz-Target", prefix + operation)
                .body(body).post("/");
    }
}
