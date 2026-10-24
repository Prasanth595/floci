package io.github.hectorvent.floci.services.emr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.emr.model.EmrCluster;
import io.github.hectorvent.floci.services.emr.model.EmrStep;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code aws.emr} events {@link EmrService} publishes, in the order it publishes them, each one
 * checked against the Management Guide's detail and its tables' severity and message.
 */
class EmrEventPublisherTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<JsonNode> published = new ArrayList<>();
    private Target target = event -> { };
    private EmrEventPublisher publisher;
    private EmrService service;

    /** A rule's target, given each event as it is published, as EventBridge's first delivery is. */
    @FunctionalInterface
    private interface Target {
        void deliver(JsonNode event) throws Exception;
    }

    @BeforeEach
    void setUp() {
        EventBridgeService eventBridge = mock(EventBridgeService.class);
        doAnswer(invocation -> {
            // The call that made the change has let go of the service by now.
            assertFalse(Thread.holdsLock(service), "published while EmrService was held");
            JsonNode event = objectMapper.createObjectNode()
                    .put("detail-type", invocation.<String>getArgument(1))
                    .set("detail", objectMapper.readTree(invocation.<String>getArgument(2)));
            published.add(event);
            target.deliver(event);
            return null;
        }).when(eventBridge).publishServiceEvent(eq("aws.emr"), anyString(), anyString(), isNull(), eq(REGION));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().emr().defaultReleaseLabel()).thenReturn("emr-7.5.0");
        publisher = new EmrEventPublisher(eventBridge, objectMapper, new RegionResolver(REGION, ACCOUNT));
        service = new EmrService(new InMemoryStorageFactory(ACCOUNT), config, new RegionResolver(REGION, ACCOUNT),
                publisher);
    }

    @Test
    void aClusterThatRunsItsStepsAndWaitsIsStartedAddsAndCompletesThemAndWaits() {
        EmrCluster cluster = service.runJobFlow(cluster("nightly", true, step("load"), step("report")), REGION);

        EmrStep load = cluster.getSteps().get(0);
        EmrStep report = cluster.getSteps().get(1);
        assertEquals(List.of("STARTING", "PENDING load", "PENDING report", "COMPLETED load", "COMPLETED report",
                "WAITING"), states());
        // As the events tell it: every step was added before the first one ran.
        assertFalse(report.getCreationDateTime().isAfter(load.getStartDateTime()));
        assertCluster(published.get(0), cluster, "STARTING", "INFO", "{\"code\":\"\"}",
                "Amazon EMR cluster " + cluster.getId() + " (nightly) was requested at "
                        + minute(cluster.getCreationDateTime()) + " and is being created.");
        assertStep(published.get(1), cluster, load, "PENDING", "INFO",
                "Step " + load.getId() + " (load) was added to Amazon EMR cluster " + cluster.getId()
                        + " (nightly) at " + minute(load.getCreationDateTime()) + " and is pending execution.");
        assertStep(published.get(3), cluster, load, "COMPLETED", "INFO",
                "Step " + load.getId() + " (load) in Amazon EMR cluster " + cluster.getId()
                        + " (nightly) completed execution at " + minute(load.getEndDateTime())
                        + ". The step started running at " + minute(load.getStartDateTime())
                        + " and took 0 minutes to complete.");
        assertEquals(report.getId(), published.get(4).at("/detail/stepId").asText());
        assertCluster(published.get(5), cluster, "WAITING", "INFO", "{\"code\":\"\"}",
                "Amazon EMR cluster " + cluster.getId() + " (nightly) finished running all pending steps at "
                        + minute(cluster.getReadyDateTime()) + ".");
    }

    @Test
    void aClusterWithoutStepsIsReadyForUse() {
        EmrCluster cluster = service.runJobFlow(cluster("idle", true), REGION);

        assertEquals(List.of("STARTING", "WAITING"), states());
        assertEquals("Amazon EMR cluster " + cluster.getId() + " (idle) was created at "
                + minute(cluster.getReadyDateTime()) + " and is ready for use.",
                published.get(1).at("/detail/message").asText());
    }

    @Test
    void aTransientClusterTerminatesAfterItsStepsWithoutWaiting() {
        EmrCluster cluster = service.runJobFlow(cluster("transient", false, step("only")), REGION);

        assertEquals(List.of("STARTING", "PENDING only", "COMPLETED only", "TERMINATED"), states());
        assertCluster(published.get(3), cluster, "TERMINATED", "INFO",
                "{\"code\":\"ALL_STEPS_COMPLETED\",\"message\":\"All steps completed.\"}",
                "Amazon EMR Cluster " + cluster.getId() + " (transient) has terminated at "
                        + minute(cluster.getEndDateTime()) + " with a reason of ALL_STEPS_COMPLETED.");
    }

    @Test
    void stepsAddedToAWaitingClusterLeaveItWaiting() {
        EmrCluster cluster = service.runJobFlow(cluster("kept", true), REGION);
        published.clear();

        service.addJobFlowSteps(cluster.getId(), List.of(step("late")));

        assertEquals(List.of("PENDING late", "COMPLETED late"), states());
    }

    @Test
    void stepsAddedToATransientClusterThatWaitedEndIt() {
        EmrCluster cluster = service.runJobFlow(cluster("no-steps-yet", false), REGION);
        published.clear();

        service.addJobFlowSteps(cluster.getId(), List.of(step("late")));

        assertEquals(List.of("PENDING late", "COMPLETED late", "TERMINATED"), states());
        assertEquals("{\"code\":\"ALL_STEPS_COMPLETED\",\"message\":\"All steps completed.\"}",
                published.get(2).at("/detail/stateChangeReason").asText());
    }

    @Test
    void terminatingPublishesForEveryClusterItEndsEvenWhenAProtectedOneFailsTheCall() {
        EmrCluster ended = service.runJobFlow(cluster("ended", true), REGION);
        EmrCluster gone = service.runJobFlow(cluster("gone", true), REGION);
        EmrCluster kept = cluster("kept", true);
        kept.setTerminationProtected(true);
        kept = service.runJobFlow(kept, REGION);
        service.terminateJobFlows(List.of(gone.getId()));
        published.clear();

        List<String> ids = List.of(ended.getId(), gone.getId(), kept.getId());
        assertThrows(AwsException.class, () -> service.terminateJobFlows(ids));

        assertEquals(List.of("TERMINATED"), states());
        assertCluster(published.get(0), service.describeCluster(ended.getId()), "TERMINATED", "INFO",
                "{\"code\":\"USER_REQUEST\",\"message\":\"Terminated by user request\"}",
                "Amazon EMR Cluster " + ended.getId() + " (ended) has terminated at "
                        + minute(service.describeCluster(ended.getId()).getEndDateTime())
                        + " with a reason of USER_REQUEST.");
    }

    @Test
    void aRefusedCallPublishesNothing() {
        EmrCluster cluster = service.runJobFlow(cluster("refusing", true), REGION);
        service.terminateJobFlows(List.of(cluster.getId()));
        published.clear();

        assertThrows(AwsException.class, () -> service.addJobFlowSteps(cluster.getId(), List.of(step("late"))));
        assertThrows(AwsException.class, () -> service.runJobFlow(new EmrCluster(), REGION));

        assertEquals(List.of(), states());
    }

    @Test
    void aTargetThatEndsTheClusterOnItsStartingEventHearsOfItAfterTheRestOfTheCall() {
        target = event -> {
            if ("STARTING".equals(event.at("/detail/state").asText())) {
                service.terminateJobFlows(List.of(event.at("/detail/clusterId").asText()));
            }
        };

        service.runJobFlow(cluster("answered", true), REGION);

        assertEquals(List.of("STARTING", "WAITING", "TERMINATED"), states());
    }

    @Test
    void aTargetCallingBackFromAnotherThreadHearsOfItAfterTheRestOfTheCall() {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        target = event -> {
            if ("STARTING".equals(event.at("/detail/state").asText())) {
                // As an API destination would, from the thread serving its request, while this one waits.
                String clusterId = event.at("/detail/clusterId").asText();
                Thread other = new Thread(() -> {
                    try {
                        service.terminateJobFlows(List.of(clusterId));
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                other.start();
                other.join();
            }
        };

        service.runJobFlow(cluster("answered", true), REGION);

        assertNull(failure.get());
        assertEquals(List.of("STARTING", "WAITING", "TERMINATED"), states());
    }

    @Test
    void aTerminationForAFailureIsCritical() throws Exception {
        EmrCluster cluster = service.runJobFlow(cluster("failing", true), REGION);
        cluster.setStateChangeReasonCode("STEP_FAILURE");
        cluster.setStateChangeMessage("Shut down as step failed");
        cluster.setEndDateTime(Instant.parse("2026-10-24T09:41:30Z"));

        JsonNode detail = objectMapper.readTree(publisher.clusterStateChange(cluster, "TERMINATED").detail());

        assertEquals("CRITICAL", detail.path("severity").asText());
        assertEquals("Amazon EMR Cluster " + cluster.getId() + " (failing) has terminated at 2026-10-24 09:41 UTC"
                + " with a reason of STEP_FAILURE.", detail.path("message").asText());
    }

    @Test
    void aCancelledStepIsAWarning() throws Exception {
        EmrCluster cluster = service.runJobFlow(cluster("cancelling", true, step("doomed")), REGION);
        EmrStep step = cluster.getSteps().get(0);
        step.setEndDateTime(Instant.parse("2026-10-24T09:41:30Z"));

        EmrEventPublisher.Event event = publisher.stepStatusChange(cluster, step, "CANCELLED");

        assertEquals("EMR Step Status Change", event.detailType());
        JsonNode detail = objectMapper.readTree(event.detail());
        assertEquals("WARN", detail.path("severity").asText());
        assertEquals("Cancellation request has succeeded for cluster step " + step.getId() + " (doomed) in Amazon EMR"
                + " cluster " + cluster.getId() + " (cancelling) at 2026-10-24 09:41 UTC, and the step is now"
                + " cancelled.", detail.path("message").asText());
    }

    private void assertCluster(JsonNode event, EmrCluster cluster, String state, String severity, String reason,
                               String message) {
        assertEquals("EMR Cluster State Change", event.path("detail-type").asText());
        JsonNode detail = event.path("detail");
        assertEquals(List.of("severity", "stateChangeReason", "name", "clusterId", "state", "message"),
                fieldNames(detail));
        assertEquals(severity, detail.path("severity").asText());
        assertEquals(reason, detail.path("stateChangeReason").asText());
        assertEquals(cluster.getName(), detail.path("name").asText());
        assertEquals(cluster.getId(), detail.path("clusterId").asText());
        assertEquals(state, detail.path("state").asText());
        assertEquals(message, detail.path("message").asText());
    }

    private void assertStep(JsonNode event, EmrCluster cluster, EmrStep step, String state, String severity,
                            String message) {
        assertEquals("EMR Step Status Change", event.path("detail-type").asText());
        JsonNode detail = event.path("detail");
        assertEquals(List.of("severity", "actionOnFailure", "stepId", "name", "clusterId", "state", "message"),
                fieldNames(detail));
        assertEquals(severity, detail.path("severity").asText());
        assertEquals("CONTINUE", detail.path("actionOnFailure").asText());
        assertEquals(step.getId(), detail.path("stepId").asText());
        assertEquals(step.getName(), detail.path("name").asText());
        assertEquals(cluster.getId(), detail.path("clusterId").asText());
        assertEquals(state, detail.path("state").asText());
        assertEquals(message, detail.path("message").asText());
    }

    /** Each event's state, a step's followed by its name. */
    private List<String> states() {
        return published.stream().map(event -> {
            JsonNode detail = event.path("detail");
            return detail.has("stepId")
                    ? detail.path("state").asText() + " " + detail.path("name").asText()
                    : detail.path("state").asText();
        }).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String minute(Instant instant) {
        return MINUTE.format(instant);
    }

    private static EmrCluster cluster(String name, boolean keepAlive, EmrStep... steps) {
        EmrCluster cluster = new EmrCluster();
        cluster.setName(name);
        cluster.setKeepJobFlowAliveWhenNoSteps(keepAlive);
        cluster.setSteps(new ArrayList<>(List.of(steps)));
        return cluster;
    }

    private static EmrStep step(String name) {
        EmrStep step = new EmrStep();
        step.setName(name);
        step.setActionOnFailure("CONTINUE");
        return step;
    }
}
