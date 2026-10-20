package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codebuild.CodeBuildService;
import io.github.hectorvent.floci.services.codedeploy.CodeDeployService;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelinePipeline;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelinePipeline.TransitionState;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class CodePipelineReviewRegressionTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String PIPELINE = "review-catch-up";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void newerExecutionFailingBuildDoesNotSupersedeOlderWaitingAtDeploy() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String older = fixture.start();
            fixture.complete(fixture.job("Build", older), true);
            fixture.awaitDeployWaiters(1);
            fixture.assertAction(older, "Build", "Succeeded");
            fixture.assertNoDeploy(older);

            String newer = fixture.start();
            JsonNode newerBuild = fixture.job("Build", newer);
            fixture.complete(newerBuild, false);
            fixture.awaitStatus(newer, "Failed");
            fixture.awaitWorkerExit(newer);
            fixture.assertAction(newer, "Build", "Failed");
            fixture.assertNoDeploy(newer);
            assertEquals(1, fixture.deployWaiters.size(), "B must fail before reaching Deploy");

            assertEquals("InProgress", fixture.status(older),
                    "A waiting at Deploy must survive B failing in Build");
            assertTrue(fixture.service.activeRuns.contains(ACCOUNT + ":" + older));
            fixture.enableDeploy();
            fixture.complete(fixture.job("Deploy", older), true);
            fixture.awaitStatus(older, "Succeeded");
            fixture.awaitWorkerExit(older);
            fixture.assertAction(older, "Deploy", "Succeeded");
            assertEquals("Failed", fixture.status(newer));
        }
    }

    @Test
    void olderExecutionIsSupersededOnlyWhenNewerReachesSameDisabledDeploy() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String older = fixture.start();
            fixture.complete(fixture.job("Build", older), true);
            fixture.awaitDeployWaiters(1);
            fixture.assertAction(older, "Build", "Succeeded");
            fixture.assertNoDeploy(older);

            String newer = fixture.start();
            JsonNode newerBuild = fixture.job("Build", newer);
            // The job is deliberately unfinished: B's real worker is still in Build, not Deploy.
            fixture.assertAction(newer, "Build", "InProgress");
            assertEquals(1, fixture.deployWaiters.size());
            assertEquals("InProgress", fixture.status(older),
                    "Starting B is not catch-up: A must remain live while B is in Build");
            assertTrue(fixture.service.activeRuns.contains(ACCOUNT + ":" + older));

            fixture.complete(newerBuild, true);
            fixture.awaitDeployWaiters(2);
            fixture.assertAction(newer, "Build", "Succeeded");
            fixture.awaitStatus(older, "Superseded");
            fixture.awaitWorkerExit(older);
            fixture.assertNoDeploy(older);
            fixture.assertNoDeploy(newer);
            assertEquals("InProgress", fixture.status(newer));

            fixture.enableDeploy();
            fixture.complete(fixture.job("Deploy", newer), true);
            fixture.awaitStatus(newer, "Succeeded");
            fixture.awaitWorkerExit(newer);
            fixture.assertAction(newer, "Deploy", "Succeeded");
            assertEquals("Superseded", fixture.status(older));
            fixture.assertNoDeploy(older);
        }
    }

    /** Custom jobs provide explicit action barriers without external services or timed sleeps. */
    private static final class Fixture implements AutoCloseable {
        private final ObjectMapper mapper = new ObjectMapper();
        private final CapturingStorageFactory storage = new CapturingStorageFactory();
        private final CodePipelineService service = new CodePipelineService(storage, mapper,
                mock(CodeBuildService.class), mock(CodeDeployService.class),
                mock(LambdaService.class), mock(S3Service.class));
        private final Set<Thread> deployWaiters = ConcurrentHashMap.newKeySet();

        private Fixture() throws Exception {
            handle("CreatePipeline", """
                    {"pipeline": {
                      "name":"review-catch-up", "executionMode":"SUPERSEDED",
                      "roleArn":"arn:aws:iam::000000000000:role/codepipeline-role",
                      "artifactStore":{"type":"S3","location":"artifacts"},
                      "stages":[
                        {"name":"Build","actions":[{"name":"BuildAction",
                          "actionTypeId":{"category":"Build","owner":"Custom",
                            "provider":"ReviewBuild","version":"1"}}]},
                        {"name":"Deploy","actions":[{"name":"DeployAction",
                          "actionTypeId":{"category":"Deploy","owner":"Custom",
                            "provider":"ReviewDeploy","version":"1"}}]}
                      ]}}
                    """);
            transition("DisableStageTransition");
            CodePipelinePipeline pipeline = storage.pipelines.getForAccount(ACCOUNT, REGION + ":" + PIPELINE)
                    .orElseThrow();
            TransitionState disabled = spy(pipeline.getTransitions().get("Deploy"));
            // Probe the actual transition read, not merely activeRuns admission or Build completion.
            // Only execution workers read this transition in this fixture (no GetPipelineState calls).
            doAnswer(invocation -> {
                deployWaiters.add(Thread.currentThread());
                return invocation.callRealMethod();
            }).when(disabled).isEnabled();
            pipeline.getTransitions().put("Deploy", disabled);
        }

        private JsonNode handle(String action, String body) throws Exception {
            return service.handle(action, mapper.readTree(body), REGION, ACCOUNT);
        }

        private String start() throws Exception {
            return handle("StartPipelineExecution", "{\"name\":\"" + PIPELINE + "\"}")
                    .path("pipelineExecutionId").asText();
        }

        private void transition(String action) throws Exception {
            handle(action, """
                    {"pipelineName":"review-catch-up","stageName":"Deploy",
                     "transitionType":"Inbound","reason":"test barrier"}
                    """);
        }

        private void enableDeploy() throws Exception {
            transition("EnableStageTransition");
        }

        private void awaitDeployWaiters(int count) {
            await().atMost(TIMEOUT).until(() -> deployWaiters.size() == count);
        }

        private JsonNode job(String stage, String execution) {
            AtomicReference<JsonNode> found = new AtomicReference<>();
            await().atMost(TIMEOUT).until(() -> {
                JsonNode response = handle("PollForJobs", """
                        {"actionTypeId":{"category":"%s","owner":"Custom",
                         "provider":"Review%s","version":"1"},"maxBatchSize":10}
                        """.formatted(stage, stage));
                for (JsonNode job : response.path("jobs")) {
                    if (execution.equals(job.path("data").path("pipelineContext")
                            .path("pipelineExecutionId").asText())) {
                        found.set(job);
                        return true;
                    }
                }
                return false;
            });
            return found.get();
        }

        private void complete(JsonNode job, boolean success) throws Exception {
            handle("AcknowledgeJob", mapper.createObjectNode().put("jobId", job.path("id").asText())
                    .put("nonce", job.path("nonce").asText()).toString());
            handle(success ? "PutJobSuccessResult" : "PutJobFailureResult", """
                    {"jobId":"%s","failureDetails":{"type":"JobFailed","message":"Build failed"}}
                    """.formatted(job.path("id").asText()));
        }

        private String status(String execution) throws Exception {
            return handle("GetPipelineExecution", """
                    {"pipelineName":"review-catch-up","pipelineExecutionId":"%s"}
                    """.formatted(execution)).path("pipelineExecution").path("status").asText();
        }

        private void awaitStatus(String execution, String expected) {
            await().atMost(TIMEOUT).untilAsserted(() -> assertEquals(expected, status(execution)));
        }

        private void awaitWorkerExit(String execution) {
            await().atMost(TIMEOUT).until(() -> !service.activeRuns.contains(ACCOUNT + ":" + execution));
        }

        private JsonNode actions(String execution) throws Exception {
            return handle("ListActionExecutions", """
                    {"pipelineName":"review-catch-up","filter":{"pipelineExecutionId":"%s"}}
                    """.formatted(execution)).path("actionExecutionDetails");
        }

        private void assertAction(String execution, String stage, String expected) throws Exception {
            int count = 0;
            for (JsonNode action : actions(execution)) {
                if (stage.equals(action.path("stageName").asText())) {
                    count++;
                    assertEquals(expected, action.path("status").asText());
                }
            }
            assertEquals(1, count, "Expected exactly one " + stage + " action for " + execution);
        }

        private void assertNoDeploy(String execution) throws Exception {
            for (JsonNode action : actions(execution)) {
                assertFalse("Deploy".equals(action.path("stageName").asText()),
                        "Disabled/superseded execution must not run Deploy: " + execution);
            }
        }

        @Override
        public void close() {
            service.shutdown();
        }
    }

    private static final class CapturingStorageFactory extends StorageFactory {
        private AccountAwareStorageBackend<CodePipelinePipeline> pipelines;

        private CapturingStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            AccountAwareStorageBackend<V> store = AccountAwareStorageBackend.inMemory(ACCOUNT);
            if ("codepipeline-pipelines.json".equals(fileName)) {
                pipelines = (AccountAwareStorageBackend<CodePipelinePipeline>) (AccountAwareStorageBackend<?>) store;
            }
            return store;
        }
    }
}
