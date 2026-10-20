package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codedeploy.model.Deployment;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelinePipeline;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodePipelineServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CodePipelineService service = new CodePipelineService(
            new InMemoryStorageFactory(),
            mapper,
            mock(CodeBuildService.class),
            mock(CodeDeployService.class),
            mock(LambdaService.class),
            mock(S3Service.class));

    /**
     * The configured interval reaches {@code scheduleWithFixedDelay}, which rejects a non-positive
     * delay. That schedule happens in {@code @PostConstruct}, so an IllegalArgumentException there
     * would leave CodePipeline unavailable rather than merely mis-scheduled.
     */
    @Test
    void nonPositiveConfiguredPollInterval_doesNotPreventStartup() {
        for (long interval : new long[] {0L, -1L, Long.MIN_VALUE}) {
            CodePipelineService configured = new CodePipelineService(
                    new InMemoryStorageFactory(),
                    mapper,
                    mock(CodeBuildService.class),
                    mock(CodeDeployService.class),
                    mock(LambdaService.class),
                    mock(S3Service.class),
                    configWithPollInterval(interval));
            try {
                assertDoesNotThrow(configured::resumePersistedExecutions,
                        "interval " + interval + " must not abort startup");
            } finally {
                configured.shutdown();
            }
        }
    }

    /**
     * The guard above is unreachable while the constructor reads only the one key, so this is what
     * keeps the message it produces from rotting. {@code enabled()} returns a primitive and is not
     * in the answers map, which is exactly the shape that used to fail as "not an interface".
     */
    @Test
    void configStubNamesAnAccessorItCannotAnswer() {
        EmulatorConfig stub = configWithPollInterval(500L);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> stub.services().codepipeline().enabled());

        assertTrue(thrown.getMessage().contains("enabled()"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("answers map"), thrown.getMessage());
    }

    /**
     * Minimal {@link EmulatorConfig} view: the constructor reads only the source poll interval, so
     * a proxy answering that avoids standing up the Quarkus config container.
     *
     * <p>Anything not in the answers map is assumed to be a further config view and is proxied in
     * turn. That assumption holds only while the constructor reads exactly one key. If a second read
     * lands and it returns a {@code String} or a primitive there is no interface to proxy, so the
     * handler says which method it could not answer instead of letting the JDK fail further down
     * with "not an interface".
     */
    private static EmulatorConfig configWithPollInterval(long intervalMs) {
        Map<String, Object> answers = Map.of("sourcePollIntervalMs", intervalMs);

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                Object answer = answers.get(method.getName());
                if (answer != null) {
                    return answer;
                }
                if (!method.getReturnType().isInterface()) {
                    throw new IllegalStateException("cannot stub " + method.getName() + "(), which returns "
                            + method.getReturnType().getSimpleName()
                            + ": add it to the answers map in configWithPollInterval");
                }
                // services() and codepipeline() return further config views; proxy those too.
                return Proxy.newProxyInstance(method.getReturnType().getClassLoader(),
                        new Class<?>[] {method.getReturnType()}, this);
            }
        };
        return (EmulatorConfig) Proxy.newProxyInstance(EmulatorConfig.class.getClassLoader(),
                new Class<?>[] {EmulatorConfig.class}, handler);
    }

    @Test
    void getPipelineTreatsMissingStoredVersionAsVersionOne() throws Exception {
        CapturingStorageFactory storageFactory = new CapturingStorageFactory();
        CodePipelineService legacyService = new CodePipelineService(
                storageFactory,
                mapper,
                mock(CodeBuildService.class),
                mock(CodeDeployService.class),
                mock(LambdaService.class),
                mock(S3Service.class));
        CodePipelinePipeline pipeline = mapper.readValue("""
                {
                    "accountId": "000000000000",
                    "region": "us-east-1",
                    "name": "legacy-pipeline",
                    "arn": "arn:aws:codepipeline:us-east-1:000000000000:legacy-pipeline",
                    "created": 1.0,
                    "updated": 1.0,
                    "declaration": {
                        "name": "legacy-pipeline",
                        "version": 1
                    }
                }
                """, CodePipelinePipeline.class);
        storageFactory.pipelineStore().putForAccount(ACCOUNT, REGION + ":legacy-pipeline", pipeline);

        try {
            JsonNode current = legacyService.handle(
                    "GetPipeline", mapper.readTree("{\"name\":\"legacy-pipeline\"}"), REGION, ACCOUNT);
            JsonNode explicitVersion = legacyService.handle(
                    "GetPipeline", mapper.readTree("{\"name\":\"legacy-pipeline\",\"version\":1}"), REGION, ACCOUNT);

            assertEquals(1, current.path("pipeline").path("version").asInt());
            assertEquals(1, explicitVersion.path("pipeline").path("version").asInt());
            AwsException missing = assertThrows(AwsException.class,
                    () -> legacyService.handle(
                            "GetPipeline", mapper.readTree("{\"name\":\"legacy-pipeline\",\"version\":2}"),
                            REGION, ACCOUNT));
            assertEquals("PipelineVersionNotFoundException", missing.getErrorCode());
        } finally {
            legacyService.shutdown();
        }
    }

    @Test
    void sourcePollingAndPipelineUpdateSerializeBaselineChanges() throws Exception {
        S3Service s3Service = mock(S3Service.class);
        S3Object source = new S3Object();
        source.setETag("\"baseline\"");
        AtomicInteger headCalls = new AtomicInteger();
        CountDownLatch pollEntered = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        when(s3Service.headObject("source-bucket", "source.zip")).thenAnswer(invocation -> {
            int call = headCalls.incrementAndGet();
            if (call == 2) {
                pollEntered.countDown();
                if (!releasePoll.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release the source poll");
                }
            }
            return source;
        });

        CodePipelineService pollingService = new CodePipelineService(
                new InMemoryStorageFactory(),
                mapper,
                mock(CodeBuildService.class),
                mock(CodeDeployService.class),
                mock(LambdaService.class),
                s3Service);
        String pipelineRequest = """
                {
                    "pipeline": {
                        "name": "polling-pipeline",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
                            "name": "Source",
                            "actions": [{
                                "name": "SourceObject",
                                "actionTypeId": {
                                    "category": "Source",
                                    "owner": "AWS",
                                    "provider": "S3",
                                    "version": "1"
                                },
                                "configuration": {
                                    "S3Bucket": "source-bucket",
                                    "S3ObjectKey": "source.zip"
                                },
                                "outputArtifacts": [{"name": "SourceOutput"}]
                            }]
                        }, {
                            "name": "Approve",
                            "actions": [{
                                "name": "ManualApproval",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }]
                    }
                }
                """;
        JsonNode request = mapper.readTree(pipelineRequest);

        try {
            pollingService.handle("CreatePipeline", request, REGION, ACCOUNT);
            pollingService.resumePersistedExecutions();
            assertTrue(pollEntered.await(2, TimeUnit.SECONDS));

            JsonNode updateRequest = mapper.readTree(pipelineRequest);
            CompletableFuture<JsonNode> update = CompletableFuture.supplyAsync(
                    () -> pollingService.handle("UpdatePipeline", updateRequest, REGION, ACCOUNT));

            Thread.sleep(150);
            assertFalse(update.isDone(), "UpdatePipeline must wait for the in-flight source poll");

            releasePoll.countDown();
            update.get(2, TimeUnit.SECONDS);
        } finally {
            releasePoll.countDown();
            pollingService.shutdown();
        }
    }

    @Test
    void stoppingCodeBuildWaitsForSuccessWithoutStoppingTheBuild() throws Exception {
        CodeBuildService codeBuildService = mock(CodeBuildService.class);
        AtomicReference<Build> currentBuild = new AtomicReference<>(build("IN_PROGRESS", false));
        CountDownLatch buildStarted = new CountDownLatch(1);
        when(codeBuildService.startBuild(anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    buildStarted.countDown();
                    return currentBuild.get();
                });
        when(codeBuildService.getBuild(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> currentBuild.get());
        CodePipelineService pipelineService = serviceWith(codeBuildService, mock(CodeDeployService.class));
        String pipelineName = "codebuild-stop-wait";

        try {
            createExternalActionPipeline(pipelineService, pipelineName, """
                    {
                        "name": "BuildAction",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "AWS",
                            "provider": "CodeBuild",
                            "version": "1"
                        },
                        "configuration": {"ProjectName": "build-project"}
                    }
                    """);
            String executionId = startExecution(pipelineService, pipelineName);
            assertTrue(buildStarted.await(2, TimeUnit.SECONDS));

            stopExecution(pipelineService, pipelineName, executionId, false);
            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopping");
            currentBuild.set(build("SUCCEEDED", true));

            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopped");
            JsonNode state = pipelineState(pipelineService, pipelineName);
            assertEquals("Stopped", state.path("stageStates").path(0)
                    .path("latestExecution").path("status").asText());
            assertEquals("Succeeded", state.path("stageStates").path(0).path("actionStates").path(0)
                    .path("latestExecution").path("status").asText());
            verify(codeBuildService, never()).stopBuild(anyString(), anyString(), anyString());
        } finally {
            pipelineService.shutdown();
        }
    }

    @Test
    void stoppingCodeDeployWaitsForFailureWithoutFailingThePipeline() throws Exception {
        CodeDeployService codeDeployService = mock(CodeDeployService.class);
        AtomicReference<Deployment> currentDeployment = new AtomicReference<>(deployment("InProgress"));
        CountDownLatch deploymentStarted = new CountDownLatch(1);
        when(codeDeployService.createDeployment(anyString(), anyString(), anyString(),
                any(), any(), anyString())).thenAnswer(invocation -> {
                    deploymentStarted.countDown();
                    return "deployment-1";
                });
        when(codeDeployService.getDeployment(anyString(), anyString()))
                .thenAnswer(invocation -> currentDeployment.get());
        CodePipelineService pipelineService = serviceWith(mock(CodeBuildService.class), codeDeployService);
        String pipelineName = "codedeploy-stop-wait";

        try {
            createExternalActionPipeline(pipelineService, pipelineName, """
                    {
                        "name": "DeployAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "CodeDeploy",
                            "version": "1"
                        },
                        "configuration": {
                            "ApplicationName": "application",
                            "DeploymentGroupName": "deployment-group"
                        }
                    }
                    """);
            String executionId = startExecution(pipelineService, pipelineName);
            assertTrue(deploymentStarted.await(2, TimeUnit.SECONDS));

            stopExecution(pipelineService, pipelineName, executionId, false);
            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopping");
            currentDeployment.set(deployment("Failed"));

            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopped");
            JsonNode state = pipelineState(pipelineService, pipelineName);
            assertEquals("Failed", state.path("stageStates").path(0)
                    .path("latestExecution").path("status").asText());
            assertEquals("Failed", state.path("stageStates").path(0).path("actionStates").path(0)
                    .path("latestExecution").path("status").asText());
            verify(codeDeployService, never()).stopDeployment(anyString(), anyString());
        } finally {
            pipelineService.shutdown();
        }
    }

    @Test
    void abandoningExternalActionsStopsWaitingWithoutStoppingThem() throws Exception {
        CodeBuildService codeBuildService = mock(CodeBuildService.class);
        CodeDeployService codeDeployService = mock(CodeDeployService.class);
        CountDownLatch actionsStarted = new CountDownLatch(2);
        Build runningBuild = build("IN_PROGRESS", false);
        Deployment runningDeployment = deployment("InProgress");
        when(codeBuildService.startBuild(anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    actionsStarted.countDown();
                    return runningBuild;
                });
        when(codeBuildService.getBuild(anyString(), anyString(), anyString())).thenReturn(runningBuild);
        when(codeDeployService.createDeployment(anyString(), anyString(), anyString(),
                any(), any(), anyString())).thenAnswer(invocation -> {
                    actionsStarted.countDown();
                    return "deployment-1";
                });
        when(codeDeployService.getDeployment(anyString(), anyString())).thenReturn(runningDeployment);
        CodePipelineService pipelineService = serviceWith(codeBuildService, codeDeployService);
        String pipelineName = "external-actions-abandon";

        try {
            createExternalActionPipeline(pipelineService, pipelineName, """
                    {
                        "name": "BuildAction",
                        "actionTypeId": {
                            "category": "Build",
                            "owner": "AWS",
                            "provider": "CodeBuild",
                            "version": "1"
                        },
                        "configuration": {"ProjectName": "build-project"}
                    },
                    {
                        "name": "DeployAction",
                        "actionTypeId": {
                            "category": "Deploy",
                            "owner": "AWS",
                            "provider": "CodeDeploy",
                            "version": "1"
                        },
                        "configuration": {
                            "ApplicationName": "application",
                            "DeploymentGroupName": "deployment-group"
                        }
                    }
                    """);
            String executionId = startExecution(pipelineService, pipelineName);
            assertTrue(actionsStarted.await(2, TimeUnit.SECONDS));

            stopExecution(pipelineService, pipelineName, executionId, true);

            waitForExecutionStatus(pipelineService, pipelineName, executionId, "Stopped");
            JsonNode state = pipelineState(pipelineService, pipelineName);
            assertEquals("Abandoned", state.path("stageStates").path(0).path("actionStates").path(0)
                    .path("latestExecution").path("status").asText());
            assertEquals("Abandoned", state.path("stageStates").path(0).path("actionStates").path(1)
                    .path("latestExecution").path("status").asText());
            verify(codeBuildService, never()).stopBuild(anyString(), anyString(), anyString());
            verify(codeDeployService, never()).stopDeployment(anyString(), anyString());
        } finally {
            pipelineService.shutdown();
        }
    }

    @Test
    void aStartAfterShutdownIsRefusedAndTheExecutionIsPersistedAsFailed() throws Exception {
        handle("CreatePipeline", """
                {
                    "pipeline": {
                        "name": "pipeline",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
                            "name": "Approve",
                            "actions": [{
                                "name": "ManualApproval",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }, {
                            "name": "Complete",
                            "actions": [{
                                "name": "ManualApprovalComplete",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }]
                    }
                }
                """);
        service.shutdown();

        AwsException refused = assertThrows(AwsException.class,
                () -> handle("StartPipelineExecution", "{\"name\": \"pipeline\"}"));

        assertEquals("ConflictException", refused.getErrorCode());
        assertEquals(400, refused.getHttpStatus());
        JsonNode summaries = handle("ListPipelineExecutions", "{\"pipelineName\": \"pipeline\"}")
                .path("pipelineExecutionSummaries");
        assertEquals(1, summaries.size());
        assertEquals("Failed", summaries.get(0).path("status").asText());
    }

    private JsonNode handle(String action, String body) throws Exception {
        return service.handle(action, mapper.readTree(body), REGION, ACCOUNT);
    }

    @Test
    void supersededExecutionLeavesDisabledTransitionWithoutStartingActions() throws Exception {
        String pipeline = "superseded-pipeline";
        String transition = """
                {"pipelineName":"%s","stageName":"External","transitionType":"Inbound","reason":"paused"}
                """.formatted(pipeline);
        try {
            createExternalActionPipeline(service, pipeline, """
                    {"name":"Approve","actionTypeId":{
                    "category":"Approval","owner":"AWS","provider":"Manual","version":"1"}}
                    """);
            handle("DisableStageTransition", transition);
            String first = startExecution(service, pipeline);
            awaitWaitingStage(service, pipeline, first, "External");
            String second = startExecution(service, pipeline);
            waitForExecutionStatus(service, pipeline, first, "Superseded");
            await().atMost(Duration.ofSeconds(3)).until(() -> !service.activeRuns.contains(ACCOUNT + ":" + first));

            handle("EnableStageTransition", transition);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertFalse(handle("ListActionExecutions", """
                            {"pipelineName":"%s","filter":{"pipelineExecutionId":"%s"}}
                            """.formatted(pipeline, second)).path("actionExecutionDetails").isEmpty()));
            assertEquals("Superseded", handle("GetPipelineExecution", """
                    {"pipelineName":"%s","pipelineExecutionId":"%s"}
                    """.formatted(pipeline, first)).path("pipelineExecution").path("status").asText());
            assertTrue(handle("ListActionExecutions", """
                    {"pipelineName":"%s","filter":{"pipelineExecutionId":"%s"}}
                    """.formatted(pipeline, first)).path("actionExecutionDetails").isEmpty());
            stopExecution(service, pipeline, second, true);
            waitForExecutionStatus(service, pipeline, second, "Stopped");
        } finally {
            service.shutdown();
        }
    }

    @Test
    void enabledTransitionCatchUpOnlySupersedesOlderWaitersAtThatTransition() throws Exception {
        CapturingStorageFactory storage = new CapturingStorageFactory();
        CodePipelineService catchUpService = new CodePipelineService(storage, mapper,
                mock(CodeBuildService.class), mock(CodeDeployService.class),
                mock(LambdaService.class), mock(S3Service.class));
        String pipelineName = "enabled-catch-up";
        try {
            createExternalActionPipeline(catchUpService, pipelineName, """
                    {"name":"Approve","actionTypeId":{
                    "category":"Approval","owner":"AWS","provider":"Manual","version":"1"}}
                    """);
            CodePipelineExecution older = waitingExecution(storage, pipelineName, "older", 1.0, "External");
            CodePipelineExecution newer = waitingExecution(storage, pipelineName, "newer", 2.0, null);
            newer.setAdmissionOrdinal(2L);
            CodePipelineExecution later = waitingExecution(storage, pipelineName, "later", 3.0, "External");
            later.setAdmissionOrdinal(4L);
            // Unsequenced legacy ties remain unordered; sequenced ties use admission order.
            CodePipelineExecution sameTime = waitingExecution(storage, pipelineName, "same-time", 2.0, "External");
            CodePipelineExecution sameTimeOlder = waitingExecution(
                    storage, pipelineName, "same-time-older", 2.0, "External");
            sameTimeOlder.setAdmissionOrdinal(1L);
            CodePipelineExecution sameTimeNewer = waitingExecution(
                    storage, pipelineName, "same-time-newer", 2.0, "External");
            sameTimeNewer.setAdmissionOrdinal(3L);
            CodePipelineExecution otherStage = waitingExecution(storage, pipelineName, "other-stage", 1.0, "Complete");
            CodePipelineExecution active = waitingExecution(storage, pipelineName, "active", 1.0, "External");
            active.setCurrentStage("External");
            CodePipelineExecution terminal = waitingExecution(storage, pipelineName, "terminal", 1.0, "External");
            terminal.setStatus("Failed");
            CodePipelineExecution parallel = waitingExecution(storage, pipelineName, "parallel", 1.0, "External");
            parallel.setExecutionMode("PARALLEL");

            // Exercise arrival at an enabled transition while the older execution is still marked
            // waiting (the window before its polling worker observes EnableStageTransition).
            Method waitForTransition = CodePipelineService.class.getDeclaredMethod("waitForTransition",
                    CodePipelinePipeline.class, CodePipelineExecution.class, String.class);
            waitForTransition.setAccessible(true);
            CodePipelinePipeline pipeline = storage.pipelineStore()
                    .getForAccount(ACCOUNT, REGION + ":" + pipelineName).orElseThrow();
            for (String mode : new String[] {"QUEUED", "PARALLEL"}) {
                newer.setExecutionMode(mode);
                waitForTransition.invoke(catchUpService, pipeline, newer, "External");
                assertEquals("InProgress", older.getStatus(), mode + " must not supersede waiters");
            }
            newer.setExecutionMode("SUPERSEDED");
            waitForTransition.invoke(catchUpService, pipeline, newer, "External");

            assertEquals("Superseded", older.getStatus());
            assertEquals("Superseded", sameTimeOlder.getStatus());
            assertNull(older.getWaitingStage());
            for (CodePipelineExecution untouched : new CodePipelineExecution[] {
                    newer, later, sameTime, sameTimeNewer, otherStage, active, parallel}) {
                assertEquals("InProgress", untouched.getStatus(), untouched.getPipelineExecutionId());
            }
            assertEquals("Failed", terminal.getStatus());
            JsonNode response = handle(catchUpService, "GetPipelineExecution", """
                    {"pipelineName":"%s","pipelineExecutionId":"later"}
                    """.formatted(pipelineName));
            assertFalse(response.path("pipelineExecution").has("waitingStage"),
                    "Internal waiting identity must not leak into the public execution response");
            assertFalse(response.path("pipelineExecution").has("admissionOrdinal"));
        } finally {
            catchUpService.shutdown();
        }
    }

    @Test
    void realAdmissionsPersistIncreasingOrdinalsDespiteEqualStartTimes() throws Exception {
        CapturingStorageFactory storage = new CapturingStorageFactory();
        // Force a wall-clock collision at the persistence boundary, without changing production now().
        // Both real StartPipelineExecution calls still traverse normal admission and worker startup.
        storage.fixedExecutionStartTime = 10.0;
        CodePipelineService pipelineService = new CodePipelineService(storage, mapper,
                mock(CodeBuildService.class), mock(CodeDeployService.class),
                mock(LambdaService.class), mock(S3Service.class));
        String pipeline = "ordinal-admission";
        try {
            createExternalActionPipeline(pipelineService, pipeline, """
                    {"name":"Approve","actionTypeId":{
                    "category":"Approval","owner":"AWS","provider":"Manual","version":"1"}}
                    """);
            CodePipelineExecution persisted = waitingExecution(storage, pipeline, "persisted", 1.0, null);
            persisted.setStatus("Succeeded");
            persisted.setAdmissionOrdinal(40L);
            // A deserialized record must seed the next ordinal, just as after process restart.
            storage.executionStore.putForAccount(ACCOUNT, REGION + ":" + pipeline + ":persisted",
                    mapper.readValue(mapper.writeValueAsBytes(persisted), CodePipelineExecution.class));

            String firstId = startExecution(pipelineService, pipeline);
            String secondId = startExecution(pipelineService, pipeline);
            CodePipelineExecution first = storage.executionStore
                    .getForAccount(ACCOUNT, REGION + ":" + pipeline + ":" + firstId).orElseThrow();
            CodePipelineExecution second = storage.executionStore
                    .getForAccount(ACCOUNT, REGION + ":" + pipeline + ":" + secondId).orElseThrow();
            assertEquals(first.getStartTime(), second.getStartTime());
            assertEquals(Long.valueOf(41L), first.getAdmissionOrdinal());
            assertEquals(Long.valueOf(42L), second.getAdmissionOrdinal());
            synchronized (second) {
                CodePipelineExecution restored = mapper.readValue(mapper.writeValueAsBytes(second),
                        CodePipelineExecution.class);
                assertEquals(second.getAdmissionOrdinal(), restored.getAdmissionOrdinal());
            }

            // Observe both actual workers reaching their first action before cleanup.
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                for (String id : new String[] {firstId, secondId}) {
                    assertFalse(handle(pipelineService, "ListActionExecutions", """
                            {"pipelineName":"%s","filter":{"pipelineExecutionId":"%s"}}
                            """.formatted(pipeline, id)).path("actionExecutionDetails").isEmpty());
                }
            });
            stopExecution(pipelineService, pipeline, firstId, true);
            stopExecution(pipelineService, pipeline, secondId, true);
            waitForExecutionStatus(pipelineService, pipeline, firstId, "Stopped");
            waitForExecutionStatus(pipelineService, pipeline, secondId, "Stopped");
        } finally {
            pipelineService.shutdown();
        }
    }

    @Test
    void equalTimeReverseWorkerArrivalCannotSupersedeNewerOrMutuallySupersede() throws Exception {
        CapturingStorageFactory storage = new CapturingStorageFactory();
        CodePipelineService pipelineService = new CodePipelineService(storage, mapper,
                mock(CodeBuildService.class), mock(CodeDeployService.class),
                mock(LambdaService.class), mock(S3Service.class));
        String pipeline = "ordinal-reverse-arrival";
        try {
            createExternalActionPipeline(pipelineService, pipeline, """
                    {"name":"Approve","actionTypeId":{
                    "category":"Approval","owner":"AWS","provider":"Manual","version":"1"}}
                    """);
            CodePipelineExecution older = waitingExecution(storage, pipeline, "older", 10.0, "External");
            older.setAdmissionOrdinal(1L);
            CodePipelineExecution newer = waitingExecution(storage, pipeline, "newer", 10.0, "External");
            newer.setAdmissionOrdinal(2L);
            Method arrive = CodePipelineService.class.getDeclaredMethod("waitForTransition",
                    CodePipelinePipeline.class, CodePipelineExecution.class, String.class);
            arrive.setAccessible(true);
            CodePipelinePipeline definition = storage.pipelineStore()
                    .getForAccount(ACCOUNT, REGION + ":" + pipeline).orElseThrow();

            // B has reached the transition before A's delayed worker catches up. A cannot kill B.
            arrive.invoke(pipelineService, definition, older, "External");
            assertEquals("InProgress", newer.getStatus());
            assertEquals("InProgress", older.getStatus());
            // B's next observation catches the older waiter; A's subsequent observation stays terminal.
            arrive.invoke(pipelineService, definition, newer, "External");
            assertEquals("Superseded", older.getStatus());
            arrive.invoke(pipelineService, definition, older, "External");
            assertEquals("InProgress", newer.getStatus());
            assertEquals("Superseded", older.getStatus());
            assertNull(older.getWaitingStage());
        } finally {
            pipelineService.shutdown();
        }
    }

    private CodePipelineExecution waitingExecution(CapturingStorageFactory storage, String pipeline,
                                                   String id, double start, String stage) {
        CodePipelineExecution execution = new CodePipelineExecution();
        execution.setAccountId(ACCOUNT);
        execution.setRegion(REGION);
        execution.setPipelineName(pipeline);
        execution.setPipelineExecutionId(id);
        execution.setStartTime(start);
        execution.setStatus("InProgress");
        execution.setExecutionMode("SUPERSEDED");
        execution.setWaitingStage(stage);
        storage.executionStore.putForAccount(ACCOUNT, REGION + ":" + pipeline + ":" + id, execution);
        return execution;
    }

    private void awaitWaitingStage(CodePipelineService pipelineService, String pipeline,
                                   String executionId, String stage) throws Exception {
        Method requireExecution = CodePipelineService.class.getDeclaredMethod("requireExecution",
                String.class, String.class, String.class, String.class);
        requireExecution.setAccessible(true);
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            CodePipelineExecution execution = (CodePipelineExecution) requireExecution.invoke(
                    pipelineService, ACCOUNT, REGION, pipeline, executionId);
            return stage.equals(execution.getWaitingStage())
                    && pipelineService.activeRuns.contains(ACCOUNT + ":" + executionId);
        });
    }

    @Test
    void startingNewExecutionDoesNotSupersedeAnActiveStage() throws Exception {
        String pipeline = "active-stage-pipeline";
        try {
            createExternalActionPipeline(service, pipeline, """
                    {"name":"Approve","actionTypeId":{
                    "category":"Approval","owner":"AWS","provider":"Manual","version":"1"}}
                    """);
            String first = startExecution(service, pipeline);
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertFalse(handle("ListActionExecutions", """
                            {"pipelineName":"%s","filter":{"pipelineExecutionId":"%s"}}
                            """.formatted(pipeline, first)).path("actionExecutionDetails").isEmpty()));
            String second = startExecution(service, pipeline);
            assertEquals("InProgress", handle("GetPipelineExecution", """
                    {"pipelineName":"%s","pipelineExecutionId":"%s"}
                    """.formatted(pipeline, first)).path("pipelineExecution").path("status").asText());
            stopExecution(service, pipeline, first, true);
            stopExecution(service, pipeline, second, true);
            waitForExecutionStatus(service, pipeline, first, "Stopped");
            waitForExecutionStatus(service, pipeline, second, "Stopped");
        } finally {
            service.shutdown();
        }
    }

    private CodePipelineService serviceWith(CodeBuildService codeBuildService,
                                            CodeDeployService codeDeployService) {
        return new CodePipelineService(
                new InMemoryStorageFactory(), mapper, codeBuildService, codeDeployService,
                mock(LambdaService.class), mock(S3Service.class));
    }

    private void createExternalActionPipeline(CodePipelineService pipelineService,
                                              String pipelineName, String actions) throws Exception {
        handle(pipelineService, "CreatePipeline", """
                {
                    "pipeline": {
                        "name": "%s",
                        "roleArn": "arn:aws:iam::000000000000:role/codepipeline-role",
                        "artifactStore": {"type": "S3", "location": "codepipeline-artifacts"},
                        "stages": [{
                            "name": "External",
                            "actions": [%s]
                        }, {
                            "name": "Complete",
                            "actions": [{
                                "name": "ManualApproval",
                                "actionTypeId": {
                                    "category": "Approval",
                                    "owner": "AWS",
                                    "provider": "Manual",
                                    "version": "1"
                                }
                            }]
                        }]
                    }
                }
                """.formatted(pipelineName, actions));
    }

    private String startExecution(CodePipelineService pipelineService, String pipelineName) throws Exception {
        return handle(pipelineService, "StartPipelineExecution", """
                {"name": "%s"}
                """.formatted(pipelineName)).path("pipelineExecutionId").asText();
    }

    private void stopExecution(CodePipelineService pipelineService, String pipelineName,
                               String executionId, boolean abandon) throws Exception {
        handle(pipelineService, "StopPipelineExecution", """
                {
                    "pipelineName": "%s",
                    "pipelineExecutionId": "%s",
                    "abandon": %s
                }
                """.formatted(pipelineName, executionId, abandon));
    }

    private JsonNode waitForExecutionStatus(CodePipelineService pipelineService, String pipelineName,
                                            String executionId, String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        JsonNode execution;
        do {
            execution = handle(pipelineService, "GetPipelineExecution", """
                    {"pipelineName": "%s", "pipelineExecutionId": "%s"}
                    """.formatted(pipelineName, executionId)).path("pipelineExecution");
            if (expectedStatus.equals(execution.path("status").asText())) {
                return execution;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Pipeline did not reach " + expectedStatus
                + "; last status was " + execution.path("status").asText());
    }

    private JsonNode pipelineState(CodePipelineService pipelineService, String pipelineName) throws Exception {
        return handle(pipelineService, "GetPipelineState", """
                {"name": "%s"}
                """.formatted(pipelineName));
    }

    private JsonNode handle(CodePipelineService pipelineService, String action, String body) throws Exception {
        return pipelineService.handle(action, mapper.readTree(body), REGION, ACCOUNT);
    }

    private Build build(String status, boolean complete) {
        Build build = new Build();
        build.setId("build-1");
        build.setBuildStatus(status);
        build.setBuildComplete(complete);
        return build;
    }

    private Deployment deployment(String status) {
        Deployment deployment = new Deployment();
        deployment.setDeploymentId("deployment-1");
        deployment.setStatus(status);
        return deployment;
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            return AccountAwareStorageBackend.inMemory(ACCOUNT);
        }
    }

    private static final class CapturingStorageFactory extends StorageFactory {
        private AccountAwareStorageBackend<CodePipelinePipeline> pipelineStore;
        private AccountAwareStorageBackend<CodePipelineExecution> executionStore;
        private Double fixedExecutionStartTime;

        private CapturingStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                         TypeReference<Map<String, V>> typeReference) {
            AccountAwareStorageBackend<V> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
            if ("codepipeline-pipelines.json".equals(fileName)) {
                pipelineStore = (AccountAwareStorageBackend<CodePipelinePipeline>) (AccountAwareStorageBackend<?>) store;
            } else if ("codepipeline-executions.json".equals(fileName)) {
                if (fixedExecutionStartTime != null) {
                    store = spy(store);
                    doAnswer(invocation -> {
                        CodePipelineExecution execution = invocation.getArgument(2);
                        execution.setStartTime(fixedExecutionStartTime);
                        return invocation.callRealMethod();
                    }).when(store).putForAccount(anyString(), anyString(), any());
                }
                executionStore = (AccountAwareStorageBackend<CodePipelineExecution>) (AccountAwareStorageBackend<?>) store;
            }
            return store;
        }

        private AccountAwareStorageBackend<CodePipelinePipeline> pipelineStore() {
            return pipelineStore;
        }
    }
}
