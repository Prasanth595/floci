package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StepFunctionsEventPublisherTest {

    private static final String SM_ARN = "arn:aws:states:eu-west-1:111122223333:stateMachine:jobs";
    private static final String EXEC_ARN = "arn:aws:states:eu-west-1:111122223333:execution:jobs:run-1";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final EventBridgeService eventBridge = mock(EventBridgeService.class);
    private final StepFunctionsEventPublisher publisher = new StepFunctionsEventPublisher(
            eventBridge, new RegionResolver("us-east-1", "000000000000"), objectMapper);

    private static Execution execution(String status) {
        Execution exec = new Execution();
        exec.setExecutionArn(EXEC_ARN);
        exec.setStateMachineArn(SM_ARN);
        exec.setName("run-1");
        exec.setInput("{\"job\":1}");
        exec.setStartDate(1_700_000_000.5);
        exec.setStatus(status);
        return exec;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> captureEntry() {
        ArgumentCaptor<List<Map<String, Object>>> cap = ArgumentCaptor.forClass(List.class);
        verify(eventBridge).putEvents(cap.capture(), eq("eu-west-1"));
        return cap.getValue().getFirst();
    }

    private JsonNode detailOf(Map<String, Object> entry) throws Exception {
        return objectMapper.readTree((String) entry.get("Detail"));
    }

    @Test
    void runningEventCarriesAwsEnvelopeAndExecutionFields() throws Exception {
        publisher.emitStatusChange(execution("RUNNING"));

        Map<String, Object> entry = captureEntry();
        assertEquals("aws.states", entry.get("Source"));
        assertEquals("Step Functions Execution Status Change", entry.get("DetailType"));
        ArrayNode resources = (ArrayNode) entry.get("Resources");
        assertEquals(1, resources.size());
        assertEquals(EXEC_ARN, resources.get(0).asText());

        JsonNode detail = detailOf(entry);
        assertEquals(EXEC_ARN, detail.path("executionArn").asText());
        assertEquals(SM_ARN, detail.path("stateMachineArn").asText());
        assertEquals("run-1", detail.path("name").asText());
        assertEquals("RUNNING", detail.path("status").asText());
        assertEquals(1_700_000_000_500L, detail.path("startDate").asLong());
        assertTrue(detail.path("stopDate").isNull());
        assertEquals("{\"job\":1}", detail.path("input").asText());
        assertTrue(detail.path("inputDetails").path("included").asBoolean());
        assertTrue(detail.path("output").isNull());
        assertTrue(detail.path("outputDetails").isNull());
        assertTrue(detail.path("error").isNull());
        assertTrue(detail.path("cause").isNull());
        assertEquals(0, detail.path("redriveCount").asInt());
        assertEquals("NOT_REDRIVABLE", detail.path("redriveStatus").asText());
        assertEquals("Execution is RUNNING and cannot be redriven", detail.path("redriveStatusReason").asText());
    }

    @Test
    void succeededEventCarriesOutputAndStopDateInMillis() throws Exception {
        Execution exec = execution("SUCCEEDED");
        exec.setOutput("{\"done\":true}");
        exec.setStopDate(1_700_000_002.25);

        publisher.emitStatusChange(exec);

        JsonNode detail = detailOf(captureEntry());
        assertEquals("SUCCEEDED", detail.path("status").asText());
        assertEquals(1_700_000_002_250L, detail.path("stopDate").asLong());
        assertEquals("{\"done\":true}", detail.path("output").asText());
        assertTrue(detail.path("outputDetails").path("included").asBoolean());
        assertEquals("NOT_REDRIVABLE", detail.path("redriveStatus").asText());
    }

    @Test
    void failedEventCarriesErrorAndCause() throws Exception {
        Execution exec = execution("FAILED");
        exec.setError("Job.Broken");
        exec.setCause("boom");
        exec.setStopDate(1_700_000_003.0);

        publisher.emitStatusChange(exec);

        JsonNode detail = detailOf(captureEntry());
        assertEquals("FAILED", detail.path("status").asText());
        assertEquals("Job.Broken", detail.path("error").asText());
        assertEquals("boom", detail.path("cause").asText());
        assertTrue(detail.path("output").isNull());
        assertEquals("REDRIVABLE", detail.path("redriveStatus").asText());
        assertTrue(detail.path("redriveStatusReason").isNull());
    }

    @Test
    void versionAndAliasArnsAreIncludedWhenTheExecutionHasThem() throws Exception {
        Execution exec = execution("RUNNING");
        exec.setStateMachineVersionArn(SM_ARN + ":3");
        exec.setStateMachineAliasArn(SM_ARN + ":prod");

        publisher.emitStatusChange(exec);

        JsonNode detail = detailOf(captureEntry());
        assertEquals(SM_ARN + ":3", detail.path("stateMachineVersionArn").asText());
        assertEquals(SM_ARN + ":prod", detail.path("stateMachineAliasArn").asText());
    }

    @Test
    void putEventsFailureIsSwallowed() {
        when(eventBridge.putEvents(any(), anyString())).thenThrow(new RuntimeException("bus down"));

        publisher.emitStatusChange(execution("RUNNING"));
    }

    @Test
    void onlyStandardExecutionArnsAreStandard() {
        assertTrue(StepFunctionsEventPublisher.isStandard(execution("RUNNING")));

        Execution express = execution("RUNNING");
        express.setExecutionArn("arn:aws:states:eu-west-1:111122223333:express:jobs:run-1:"
                + "8f2b0c1e-5c4a-4b8e-9a51-3d6f1e2a7b90");
        assertFalse(StepFunctionsEventPublisher.isStandard(express));
    }
}
