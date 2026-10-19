package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.restassured.path.json.JsonPath;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;

/** Reads Web Function metrics back with GetMetricStatistics, as a client does. */
final class LambdaWebMetricStatistics {

    private LambdaWebMetricStatistics() {
    }

    /** The statistic over the last ten minutes, once it reaches {@code expected} or after 15 seconds. */
    static double await(String namespace, String metric, Map<String, String> dimensions, String statistic,
                        double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        double value = of(namespace, metric, dimensions, statistic);
        while (value != expected && System.nanoTime() < deadline) {
            Thread.sleep(200);
            value = of(namespace, metric, dimensions, statistic);
        }
        return value;
    }

    /** {@code Sum} and {@code SampleCount} add up over the periods, {@code Maximum} is the largest. */
    static double of(String namespace, String metric, Map<String, String> dimensions, String statistic) {
        RestAssuredJsonUtils.configureAwsContentTypes();
        Instant now = Instant.now();
        String filter = dimensions.entrySet().stream()
                .map(each -> "{\"Name\":\"" + each.getKey() + "\",\"Value\":\"" + each.getValue() + "\"}")
                .collect(Collectors.joining(","));
        JsonPath result = given().contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "GraniteServiceVersion20100801.GetMetricStatistics")
                .body("{\"Namespace\":\"" + namespace + "\",\"MetricName\":\"" + metric + "\",\"Dimensions\":[" + filter
                        + "],\"StartTime\":" + now.minusSeconds(600).getEpochSecond() + ",\"EndTime\":"
                        + now.plusSeconds(60).getEpochSecond() + ",\"Period\":60,\"Statistics\":[\"" + statistic + "\"]}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath();
        List<Float> values = result.getList("Datapoints." + statistic, Float.class);
        if ("Maximum".equals(statistic)) {
            return values.stream().mapToDouble(Float::doubleValue).max().orElse(0);
        }
        return values.stream().mapToDouble(Float::doubleValue).sum();
    }
}
