package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@QuarkusTest
class SsmDeliveryIntegerIntegrationTest extends SsmCommandIntegrationFixture {
    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-west-2";
    @Inject ObjectMapper mapper;

    static Stream<Arguments> nonNumericTimeouts() {
        return Stream.of(
                Arguments.of("\"30\"", "STRING_VALUE"),
                Arguments.of("\"bad\"", "STRING_VALUE"),
                Arguments.of("{}", "OBJECT"),
                Arguments.of("true", "TRUE_VALUE"),
                Arguments.of("false", "FALSE_VALUE"),
                Arguments.of("[]", "ARRAY"));
    }

    @ParameterizedTest
    @MethodSource("nonNumericTimeouts")
    void nonNumericTimeoutIsRejectedBeforeAllocation(String input, String token) throws Exception {
        String instance = "i-integer-" + UUID.randomUUID();
        try {
            ObjectNode request = request(instance);
            Response rejected = sendWithTimeout(request, input);
            assertAll("serialization rejection leaves owned work unchanged",
                    () -> assertEquals(400, rejected.statusCode()),
                    () -> assertEquals("SerializationException", rejected.jsonPath().getString("__type")),
                    () -> assertEquals(token + " can not be converted to a Long",
                            rejected.jsonPath().getString("message")),
                    () -> assertTrue(RequestScopes.callAs(ACCOUNT, REGION,
                            () -> commandService.listCommands(null, instance, REGION)).isEmpty()),
                    () -> verifyNoInteractions(executor));
            request.put("TimeoutSeconds", 30);
            Response retry = send(request);
            assertEquals(200, retry.statusCode());
            assertEquals(30, retry.jsonPath().getInt("Command.TimeoutSeconds"));
            assertEquals(1, RequestScopes.callAs(ACCOUNT, REGION,
                    () -> commandService.listCommands(null, instance, REGION)).size());
        } finally {
            cleanupCommands(ACCOUNT, REGION, instance);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"30.0", "30.5", "30.9", "3e1"})
    void numericTimeoutsAreTruncatedToTheDeliveryAllowance(String input) throws Exception {
        String instance = "i-integer-" + UUID.randomUUID();
        try {
            ObjectNode request = request(instance);
            Response response = sendWithTimeout(request, input);
            assertEquals(200, response.statusCode());
            assertEquals(30, response.jsonPath().getInt("Command.TimeoutSeconds"));
            assertEquals(30, RequestScopes.callAs(ACCOUNT, REGION,
                    () -> commandService.listCommands(null, instance, REGION)).getFirst().getTimeoutSeconds());
            verify(executor).supports(ACCOUNT, instance, "AWS-RunShellScript");
            verifyNoMoreInteractions(executor);
        } finally {
            cleanupCommands(ACCOUNT, REGION, instance);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"29.9", "-2147483649", "-1e400"})
    void normalizedValuesStillPassThroughTheDeliveryMinimum(String input) throws Exception {
        String instance = "i-integer-" + UUID.randomUUID();
        try {
            ObjectNode request = request(instance);
            Response rejected = sendWithTimeout(request, input);
            assertAll(
                    () -> assertEquals(400, rejected.statusCode()),
                    () -> assertEquals("ValidationException", rejected.jsonPath().getString("__type")),
                    () -> assertTrue(rejected.jsonPath().getString("message")
                            .contains("greater than or equal to 30")),
                    () -> assertTrue(RequestScopes.callAs(ACCOUNT, REGION,
                            () -> commandService.listCommands(null, instance, REGION)).isEmpty()),
                    () -> verifyNoInteractions(executor));
            request.put("TimeoutSeconds", 30);
            assertEquals(200, send(request).statusCode());
        } finally {
            cleanupCommands(ACCOUNT, REGION, instance);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"30", "2592000", "missing", "null"})
    void integersAndExistingDefaultsRemainUnchanged(String input) throws Exception {
        String instance = "i-integer-" + UUID.randomUUID();
        try {
            ObjectNode request = request(instance);
            if (!"missing".equals(input)) {
                request.set("TimeoutSeconds", mapper.readTree(input));
            }
            Response response = send(request);
            assertEquals(200, response.statusCode());
            int expected = "missing".equals(input) || "null".equals(input) ? 3600 : Integer.parseInt(input);
            assertEquals(expected, response.jsonPath().getInt("Command.TimeoutSeconds"));
        } finally {
            cleanupCommands(ACCOUNT, REGION, instance);
        }
    }

    @Test
    void internalHookKeepsItsOneSecondBudget() {
        String instance = "i-integer-" + UUID.randomUUID();
        try {
            RequestScopes.runAs(ACCOUNT, REGION, () -> commandService.sendCommandToInstance(instance,
                    "AWS-RunShellScript", Map.of("commands", List.of("echo hook")), 1, REGION));
            assertEquals(1, RequestScopes.callAs(ACCOUNT, REGION,
                    () -> commandService.listCommands(null, instance, REGION)).getFirst().getTimeoutSeconds());
            verifyNoInteractions(executor);
        } finally {
            cleanupCommands(ACCOUNT, REGION, instance);
        }
    }

    private ObjectNode request(String instance) {
        ObjectNode request = mapper.createObjectNode();
        request.put("DocumentName", "AWS-RunShellScript");
        request.putArray("InstanceIds").add(instance);
        request.putObject("Parameters").putArray("commands").add("echo integer");
        return request;
    }

    private Response sendWithTimeout(ObjectNode request, String timeout) {
        String body = request.toString();
        return call(ACCOUNT, REGION, "AmazonSSM.SendCommand",
                body.substring(0, body.length() - 1) + ",\"TimeoutSeconds\":" + timeout + "}");
    }

    private Response send(JsonNode request) {
        return call(ACCOUNT, REGION, "AmazonSSM.SendCommand", request.toString());
    }
}
