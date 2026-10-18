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
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

@QuarkusTest
class SsmDeliveryIntegerIntegrationTest extends SsmCommandIntegrationFixture {
    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-west-2";
    @Inject ObjectMapper mapper;

    @ParameterizedTest
    @ValueSource(strings = {"30.5", "30.0", "4294967326", "\"30\"", "\"bad\"", "{}", "true", "false", "[]", "2147483648", "-2147483649"})
    void invalidWireIntegerIsRejectedBeforeAllocation(String input) throws Exception {
        String instance = "i-integer-" + UUID.randomUUID();
        try {
            ObjectNode request = request(instance);
            request.set("TimeoutSeconds", mapper.readTree(input));
            Response rejected = send(request);
            assertAll("no truncation, narrowing or default substitution",
                    () -> assertEquals(400, rejected.statusCode()),
                    () -> assertEquals("ValidationException", rejected.jsonPath().getString("__type")),
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

    private Response send(JsonNode request) {
        return call(ACCOUNT, REGION, "AmazonSSM.SendCommand", request.toString());
    }
}
