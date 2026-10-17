package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A synchronous execution is an Express one, which AWS ends with States.Timeout once it has run
 * for its maximum execution time. Once the caller has been answered TIMED_OUT, the execution must
 * not go on to run later states or report another outcome.
 */
class AslExecutorSyncTimeoutTest {

    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:000000000000:function:slow";
    private static final Set<String> TERMINAL_EVENTS =
            Set.of("ExecutionSucceeded", "ExecutionFailed", "ExecutionTimedOut", "ExecutionAborted");

    @Test
    void executionPastItsMaximumTimeRunsNoFurtherStateAndStaysTimedOut() {
        AslExecutor executor = newExecutor(mock(LambdaExecutorService.class), mock(LambdaFunctionStore.class));
        StateMachine stateMachine = stateMachine("""
                {"StartAt":"Pause","States":{
                  "Pause":{"Type":"Wait","Seconds":2,"Next":"After"},
                  "After":{"Type":"Pass","End":true}}}
                """);
        Execution execution = execution(stateMachine);
        List<HistoryEvent> history = new CopyOnWriteArrayList<>();

        executor.executeSync(stateMachine, execution, history, null, (updated, events) -> {
        }, Duration.ofSeconds(1));
        assertEquals("TIMED_OUT", execution.getStatus());

        await().atMost(Duration.ofSeconds(10))
                .until(() -> history.stream().anyMatch(event -> TERMINAL_EVENTS.contains(event.getType())));
        assertEquals("TIMED_OUT", execution.getStatus());
        assertEquals("ExecutionTimedOut", history.getLast().getType());
        assertFalse(history.stream().anyMatch(event -> "PassStateEntered".equals(event.getType())),
                "the state after the timeout ran");
    }

    @Test
    void lastStateFinishingPastTheMaximumTimeStillEndsTimedOut() {
        LambdaExecutorService lambdaExecutor = mock(LambdaExecutorService.class);
        LambdaFunctionStore functionStore = mock(LambdaFunctionStore.class);
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName("slow");
        function.setFunctionArn(FUNCTION_ARN);
        when(functionStore.get("us-east-1", "slow")).thenReturn(Optional.of(function));
        when(lambdaExecutor.invoke(eq(function), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    Thread.sleep(2000);
                    return new InvokeResult(200, null, "{}".getBytes(StandardCharsets.UTF_8), null, "slow-request");
                });
        AslExecutor executor = newExecutor(lambdaExecutor, functionStore);
        StateMachine stateMachine = stateMachine("""
                {"StartAt":"Slow","States":{"Slow":{"Type":"Task","Resource":"%s","End":true}}}
                """.formatted(FUNCTION_ARN));
        Execution execution = execution(stateMachine);
        List<HistoryEvent> history = new CopyOnWriteArrayList<>();

        executor.executeSync(stateMachine, execution, history, null, (updated, events) -> {
        }, Duration.ofSeconds(1));
        assertEquals("TIMED_OUT", execution.getStatus());

        await().atMost(Duration.ofSeconds(10))
                .until(() -> history.stream().anyMatch(event -> TERMINAL_EVENTS.contains(event.getType())));
        assertEquals("TIMED_OUT", execution.getStatus());
        assertEquals("ExecutionTimedOut", history.getLast().getType());
    }

    private static AslExecutor newExecutor(LambdaExecutorService lambdaExecutor, LambdaFunctionStore functionStore) {
        ObjectMapper objectMapper = new ObjectMapper();
        return AslExecutorFixture.builder()
                .lambdaExecutor(lambdaExecutor)
                .functionStore(functionStore)
                .objectMapper(objectMapper)
                .maxWaitSeconds(30)
                .build();
    }

    private static StateMachine stateMachine(String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("sync-timeout-test");
        stateMachine.setStateMachineArn(
                "arn:aws:states:us-east-1:000000000000:stateMachine:sync-timeout-test");
        stateMachine.setDefinition(definition);
        return stateMachine;
    }

    private static Execution execution(StateMachine stateMachine) {
        Execution execution = new Execution();
        execution.setName("sync-timeout-exec");
        execution.setExecutionArn(
                "arn:aws:states:us-east-1:000000000000:execution:sync-timeout-test:sync-timeout-exec");
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{}");
        return execution;
    }
}
