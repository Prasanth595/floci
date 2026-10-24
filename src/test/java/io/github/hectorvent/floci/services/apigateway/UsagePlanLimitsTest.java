package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.ApiStage;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Limits;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Quota;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan.Throttle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsagePlanLimitsTest {

    private static Map<String, Object> request(Object... keyValues) {
        Map<String, Object> request = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            request.put((String) keyValues[i], keyValues[i + 1]);
        }
        return request;
    }

    private static void assertRefused(Executable call, String code, int status, String message) {
        AwsException e = assertThrows(AwsException.class, call);
        assertEquals(code, e.getErrorCode());
        assertEquals(status, e.getHttpStatus());
        assertEquals(message, e.getMessage());
    }

    @Test
    void aRequestWithoutLimitsHasNoStagesThrottleOrQuota() {
        assertEquals(new Limits(List.of(), null, null), UsagePlanLimits.fromRequest(request("name", "p")));
    }

    @Test
    void throttleFieldsLeftOutReadAsZero() {
        assertEquals(new Throttle(10, 0.0),
                UsagePlanLimits.fromRequest(request("throttle", Map.of("burstLimit", 10))).throttle());
        assertEquals(new Throttle(0, 5.5),
                UsagePlanLimits.fromRequest(request("throttle", Map.of("rateLimit", "5.5"))).throttle());
    }

    @Test
    void quotaOffsetDefaultsToZeroAndNumbersMayBeStrings() {
        assertEquals(new Quota(100, 0, "WEEK"),
                UsagePlanLimits.fromRequest(request("quota", Map.of("limit", 100, "period", "WEEK"))).quota());
        assertEquals(new Quota(100, 2, "WEEK"), UsagePlanLimits.fromRequest(
                request("quota", Map.of("limit", "100", "offset", "2", "period", "WEEK"))).quota());
    }

    @Test
    void stagesKeepTheirMethodThrottlesAndAnEmptyOneIsDropped() {
        Limits limits = UsagePlanLimits.fromRequest(request(
                "throttle", Map.of("burstLimit", 20, "rateLimit", 10),
                "apiStages", List.of(
                        Map.of("apiId", "a", "stage", "v1",
                                "throttle", Map.of("/pets/GET", Map.of("burstLimit", 10, "rateLimit", 5))),
                        Map.of("apiId", "a", "stage", "v2", "throttle", Map.of()))));

        assertEquals(List.of(new ApiStage("a", "v1", Map.of("/pets/GET", new Throttle(10, 5.0))),
                new ApiStage("a", "v2")), limits.apiStages());
    }

    // The storage backends persist plans with a plain ObjectMapper, so this is the shape on disk.
    @Test
    void aPlanSurvivesTheStorageJsonRoundTripAndOlderPlansStillLoad() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UsagePlan plan = new UsagePlan();
        plan.setId("p");
        plan.applyLimits(new Limits(List.of(new ApiStage("a", "v1", Map.of("/pets/GET", new Throttle(10, 5.0))),
                new ApiStage("a", "v2")), new Throttle(20, 10.0), new Quota(1000, 3, "MONTH")));

        String json = mapper.writeValueAsString(plan);
        assertFalse(json.contains("\"limits\""), json);
        assertEquals(plan.limits(), mapper.readValue(json, UsagePlan.class).limits());

        UsagePlan older = mapper.readValue(
                "{\"id\":\"p\",\"name\":\"n\",\"apiStages\":[{\"apiId\":\"a\",\"stage\":\"v1\"}]}", UsagePlan.class);
        assertEquals(new Limits(List.of(new ApiStage("a", "v1")), null, null), older.limits());
    }

    static Stream<Arguments> refusedCreates() {
        String throttle = "Usage Plan throttle rate limit and burst limit must be numeric and greater than zero";
        String limit = "Usage Plan quota limit must be a non-negative numeric";
        return Stream.of(
                Arguments.of(Map.of("quota", Map.of("limit", 100)), "BadRequestException", 400,
                        "Invalid Usage Plan quota period specified. Must be one of [DAY, WEEK, MONTH]"),
                Arguments.of(Map.of("quota", Map.of("limit", 100, "period", "YEAR")), "ValidationException", 400,
                        "1 validation error detected: Value 'YEAR' at 'createUsagePlanInput.quota.period' failed to "
                                + "satisfy constraint: Member must satisfy enum value set: [MONTH, WEEK, DAY]"),
                Arguments.of(Map.of("quota", Map.of("period", "DAY")), "BadRequestException", 400, limit),
                Arguments.of(Map.of("quota", Map.of("limit", -1, "period", "DAY")), "BadRequestException", 400, limit),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "DAY", "offset", 1)), "BadRequestException", 400,
                        "Usage Plan quota offset must be zero in the DAY period"),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "WEEK", "offset", 7)), "BadRequestException", 400,
                        "Usage Plan quota offset must be between 0 and 6 inclusive in the WEEK period"),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "WEEK", "offset", -1)), "BadRequestException", 400,
                        "Usage Plan quota offset must be between 0 and 6 inclusive in the WEEK period"),
                Arguments.of(Map.of("quota", Map.of("limit", 1, "period", "MONTH", "offset", 28)), "BadRequestException",
                        400, "Usage Plan quota offset must be between 0 and 27 inclusive in the MONTH period"),
                Arguments.of(Map.of("throttle", Map.of("burstLimit", -1, "rateLimit", 1)), "BadRequestException", 400,
                        throttle),
                Arguments.of(Map.of("throttle", Map.of("burstLimit", 1.5)), "BadRequestException", 400, throttle),
                Arguments.of(Map.of("throttle", Map.of("rateLimit", "abc")), "BadRequestException", 400, throttle),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", "v1",
                                "throttle", Map.of("/pets/GET", Map.of("burstLimit", 1))))), "BadRequestException", 400,
                        "Can not define fine grain throttling if usage plan throttle is not defined."),
                Arguments.of(Map.of("throttle", Map.of("burstLimit", 1), "apiStages", List.of(Map.of("apiId", "a",
                                "stage", "v1", "throttle", Map.of("/pets/GET", Map.of("burstLimit", -1))))),
                        "BadRequestException", 400, throttle),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", "v1"),
                                Map.of("apiId", "a", "stage", "v1"))), "ConflictException", 409,
                        "API Stage is already defined for Usage Plan: a:v1"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("stage", "v1"))), "BadRequestException", 400,
                        "Invalid API Stage specified"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a"))), "BadRequestException", 400,
                        "Invalid API Stage specified"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "", "stage", "v1"))), "BadRequestException",
                        400, "Invalid API Stage specified"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", ""))), "BadRequestException",
                        400, "Invalid API Stage specified"),
                Arguments.of(stageThrottle("/GET"), "BadRequestException", 400,
                        "Invalid method {resourcePath: /GET,method: } specified"),
                Arguments.of(stageThrottle(""), "BadRequestException", 400,
                        "Invalid method {resourcePath: ,method: } specified"),
                Arguments.of(stageThrottle("/pets/"), "BadRequestException", 400,
                        "Invalid method {resourcePath: /pets/,method: } specified"),
                Arguments.of(stageThrottle("GET"), "BadRequestException", 400,
                        "Invalid method {resourcePath: GET,method: } specified"));
    }

    /** A create request with a plan throttle and stage a:v1 throttling {@code methodKey}. */
    private static Map<String, Object> stageThrottle(String methodKey) {
        return Map.of("throttle", Map.of("burstLimit", 20, "rateLimit", 10), "apiStages", List.of(Map.of("apiId", "a",
                "stage", "v1", "throttle", Map.of(methodKey, Map.of("burstLimit", 5, "rateLimit", 2)))));
    }

    // Floci does not check that the method exists, so keys AWS refuses for that reason are kept.
    @Test
    void aMethodKeyIsOnlyCheckedForHavingAMethod() {
        assertEquals(Map.of("//GET", new Throttle(5, 2.0)),
                UsagePlanLimits.fromRequest(stageThrottle("//GET")).apiStages().get(0).throttle());
        assertEquals(Map.of("/pets/GET/extra", new Throttle(5, 2.0)),
                UsagePlanLimits.fromRequest(stageThrottle("/pets/GET/extra")).apiStages().get(0).throttle());
    }

    static Stream<Arguments> wrongJsonTypes() {
        String list = "Start of list found where not expected";
        String notStructure = "Expected null";
        String structureFound = "Start of structure or map found where not expected.";
        Map<String, Object> planThrottle = Map.of("burstLimit", 1, "rateLimit", 1);
        return Stream.of(
                Arguments.of(Map.of("quota", "invalid"), notStructure),
                Arguments.of(Map.of("throttle", List.of()), list),
                Arguments.of(Map.of("apiStages", "x"), "Expected list or null"),
                Arguments.of(Map.of("apiStages", List.of("x")), notStructure),
                Arguments.of(Map.of("throttle", planThrottle, "apiStages", List.of(Map.of("apiId", "a", "stage", "v1",
                        "throttle", Map.of("/pets/GET", 5)))), notStructure),
                Arguments.of(Map.of("quota", List.of()), list),
                Arguments.of(Map.of("throttle", 5), notStructure),
                Arguments.of(Map.of("apiStages", Map.of()), "Expected list or null"),
                Arguments.of(Map.of("apiStages", List.of(List.of())), list),
                Arguments.of(Map.of("throttle", planThrottle, "apiStages", List.of(Map.of("apiId", "a", "stage", "v1",
                        "throttle", List.of()))), list),
                Arguments.of(Map.of("throttle", planThrottle, "apiStages", List.of(Map.of("apiId", "a", "stage", "v1",
                        "throttle", Map.of("/pets/GET", List.of())))), list),
                // The body is read whole before anything in it is checked, so a wrong type outranks a bad quota.
                Arguments.of(Map.of("quota", Map.of("limit", 1), "throttle", List.of()), list),
                // Scalar members, measured on API Gateway: text takes no list, map, number or boolean,
                // and a number takes no list or map.
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", List.of(), "stage", "v1"))), list),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", Map.of(), "stage", "v1"))), structureFound),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", List.of("v1")))), list),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", Map.of("a", 1)))),
                        structureFound),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", 5, "stage", "v1"))),
                        "class com.amazon.coral.value.json.numbers.TruncatingBigNumber can not be converted to an String"),
                Arguments.of(Map.of("apiStages", List.of(Map.of("apiId", "a", "stage", true))),
                        "class java.lang.Boolean can not be converted to an String"),
                Arguments.of(Map.of("throttle", Map.of("burstLimit", List.of())), list),
                Arguments.of(Map.of("quota", Map.of("period", Map.of())), structureFound),
                Arguments.of(Map.of("name", List.of()), list));
    }

    @ParameterizedTest
    @MethodSource("wrongJsonTypes")
    void createRefusesAWrongJsonTypeAsASerializationError(Map<String, Object> request, String message) {
        assertRefused(() -> UsagePlanLimits.fromRequest(request), "SerializationException", 400, message);
    }

    @Test
    void jsonNullReadsAsAbsent() {
        assertEquals(new Limits(List.of(), null, null),
                UsagePlanLimits.fromRequest(request("throttle", null, "quota", null, "apiStages", null)));
        assertEquals(List.of(new ApiStage("a", "v1")), UsagePlanLimits.fromRequest(
                request("apiStages", List.of(request("apiId", "a", "stage", "v1", "throttle", null)))).apiStages());
    }

    @ParameterizedTest
    @MethodSource("refusedCreates")
    void createRefusesWhatApiGatewayRefuses(Map<String, Object> request, String code, int status, String message) {
        assertRefused(() -> UsagePlanLimits.fromRequest(request), code, status, message);
    }
}
