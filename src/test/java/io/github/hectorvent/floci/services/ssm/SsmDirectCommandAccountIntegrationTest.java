package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ssm.model.Command;
import io.github.hectorvent.floci.services.ssm.model.CommandInvocation;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
class SsmDirectCommandAccountIntegrationTest {
    @Inject StorageFactory storageFactory;
    @Inject RegionResolver regionResolver;
    @InjectMock SsmDirectCommandExecutor executor;
    @Inject SsmCommandService commandService;
    @TestHTTPResource URI endpoint;

    @BeforeEach
    void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        reset(executor);
    }

    @ParameterizedTest
    @CsvSource({
            "000000000000,us-east-1,false", "000000000000,us-east-1,true",
            "222222222222,us-west-2,false", "222222222222,us-west-2,true",
            "333333333333,cn-north-1,false", "333333333333,cn-north-1,true"
    })
    void directWorkerRetainsRequestOwnerForResultAndFallback(String account, String region, boolean fallback)
            throws Exception {
        String instance = "i-scope-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<String> workerAccount = new AtomicReference<>();
        AtomicReference<String> workerRegion = new AtomicReference<>();
        AtomicReference<String> workerPartition = new AtomicReference<>();
        when(executor.supports(account, instance, "AWS-RunShellScript")).thenReturn(true);
        when(executor.executeIfSupported(eq(account), eq(instance), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenAnswer(invocation -> {
                    worker.set(Thread.currentThread());
                    workerAccount.set(regionResolver.getAccountId());
                    workerRegion.set(regionResolver.getRegion());
                    workerPartition.set(regionResolver.getPartition());
                    entered.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                    return fallback ? Optional.empty() : Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                            "Success", "owned output", "owned stderr", 0, Instant.now(), Instant.now()));
                });
        AccountAwareStorageBackend<Command> commands = storageFactory.create(
                "ssm", "ssm-commands.json", new TypeReference<>() {});
        AccountAwareStorageBackend<CommandInvocation> invocations = storageFactory.create(
                "ssm", "ssm-invocations.json", new TypeReference<>() {});
        String commandId = call(account, region, "AmazonSSM.SendCommand", Map.of(
                "DocumentName", "AWS-RunShellScript", "InstanceIds", List.of(instance),
                "Parameters", Map.of("commands", List.of("echo owned")), "TimeoutSeconds", 60))
                .jsonPath().getString("Command.CommandId");
        assertNotNull(commandId);
        String commandKey = region + "::" + commandId;
        String invocationKey = commandKey + "::" + instance;
        try {
            assertTrue(commands.getForAccount(account, commandKey).isPresent());
            assertTrue(invocations.getForAccount(account, invocationKey).isPresent());
            assertEquals("InProgress", commands.getForAccount(account, commandKey).orElseThrow().getStatus());
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Worker did not resolve its issuing account's invocation");
            assertEquals(account, workerAccount.get());
            assertEquals(region, workerRegion.get());
            assertEquals(AwsRegions.partitionFor(region), workerPartition.get());
            release.countDown();
            worker.get().join(5000);
            assertFalse(worker.get().isAlive(), "Direct worker did not finish");
            Response result = call(account, region, "AmazonSSM.GetCommandInvocation", Map.of(
                    "CommandId", commandId, "InstanceId", instance));
            assertEquals(fallback ? "Pending" : "Success", result.jsonPath().getString("Status"));
            if (fallback) {
                Response poll = call(account, region, "AmazonSSMMessageDeliveryService.GetMessages",
                        Map.of("Destination", instance, "MessagesRequestId", "owned-request"));
                assertEquals(1, poll.jsonPath().getList("Messages").size());
                assertEquals("aws.ssm.sendCommand." + region, poll.jsonPath().getString("Messages[0].Topic"));
            } else {
                assertEquals("owned output", result.jsonPath().getString("StandardOutputContent"));
                assertEquals("owned stderr", result.jsonPath().getString("StandardErrorContent"));
                assertEquals("Success", commands.getForAccount(account, commandKey).orElseThrow().getStatus());
                assertEquals(1, commands.getForAccount(account, commandKey).orElseThrow().getCompletedCount());
            }
            for (String other : List.of("000000000000", "222222222222", "333333333333")) {
                if (!other.equals(account)) {
                    assertTrue(commands.getForAccount(other, commandKey).isEmpty());
                    assertTrue(invocations.getForAccount(other, invocationKey).isEmpty());
                }
            }
        } finally {
            release.countDown();
            assertAll("release worker and remove every owned fixture",
                    () -> joinWorker(worker),
                    () -> deleteCommand(account, region, commandId, instance));
        }
    }

    @ParameterizedTest
    @CsvSource({"333333333333,us-west-2", "222222222222,cn-north-1"})
    void blockedOwnerDoesNotDelayOtherScopeAndCancellationSurvivesCompletion(String otherAccount, String otherRegion)
            throws Exception {
        String account = "222222222222";
        String region = "us-west-2";
        String instance = "i-scope-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch otherEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Thread> blockedWorker = new AtomicReference<>();
        AtomicReference<Thread> otherWorker = new AtomicReference<>();
        when(executor.supports(anyString(), eq(instance), eq("AWS-RunShellScript"))).thenReturn(true);
        when(executor.executeIfSupported(anyString(), eq(instance), eq("AWS-RunShellScript"), any(), eq(60)))
                .thenAnswer(invocation -> {
                    assertEquals(invocation.getArgument(0), regionResolver.getAccountId());
                    if (account.equals(regionResolver.getAccountId()) && region.equals(regionResolver.getRegion())) {
                        blockedWorker.set(Thread.currentThread());
                        entered.countDown();
                        assertTrue(release.await(10, TimeUnit.SECONDS));
                    } else {
                        assertEquals(otherAccount, regionResolver.getAccountId());
                        assertEquals(otherRegion, regionResolver.getRegion());
                        otherWorker.set(Thread.currentThread());
                        otherEntered.countDown();
                    }
                    return Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                            "Success", "scope output", "", 0, Instant.now(), Instant.now()));
                });
        Map<String, Object> request = Map.of("DocumentName", "AWS-RunShellScript", "InstanceIds", List.of(instance),
                "Parameters", Map.of("commands", List.of("echo scope")), "TimeoutSeconds", 60);
        String firstId = call(account, region, "AmazonSSM.SendCommand", request)
                .jsonPath().getString("Command.CommandId");
        String secondId = null;
        try {
            assertOwnedInvocation(account, region, firstId, instance);
            assertTrue(entered.await(3, TimeUnit.SECONDS), "Owner worker did not resolve its stored invocation");
            secondId = call(otherAccount, otherRegion, "AmazonSSM.SendCommand", request)
                    .jsonPath().getString("Command.CommandId");
            assertOwnedInvocation(otherAccount, otherRegion, secondId, instance);
            assertTrue(otherEntered.await(3, TimeUnit.SECONDS), "Unrelated scope was blocked by direct execution");
            otherWorker.get().join(3000);
            assertFalse(otherWorker.get().isAlive());
            assertEquals("Success", call(otherAccount, otherRegion, "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", secondId, "InstanceId", instance)).jsonPath().getString("Status"));
            call(account, region, "AmazonSSM.CancelCommand", Map.of("CommandId", firstId));
            release.countDown();
            blockedWorker.get().join(3000);
            assertFalse(blockedWorker.get().isAlive());
            assertEquals("Cancelled", call(account, region, "AmazonSSM.GetCommandInvocation",
                    Map.of("CommandId", firstId, "InstanceId", instance)).jsonPath().getString("Status"));
            assertTrue(call(account, region, "AmazonSSMMessageDeliveryService.GetMessages",
                    Map.of("Destination", instance)).jsonPath().getList("Messages").isEmpty());
        } finally {
            release.countDown();
            String otherId = secondId;
            assertAll("release both workers and remove every owned fixture",
                    () -> joinWorker(blockedWorker),
                    () -> joinWorker(otherWorker),
                    () -> deleteCommand(account, region, firstId, instance),
                    () -> {
                        if (otherId != null) {
                            deleteCommand(otherAccount, otherRegion, otherId, instance);
                        }
                    });
        }
    }

    private void assertOwnedInvocation(String account, String region, String commandId, String instance) {
        AccountAwareStorageBackend<Command> commands = storageFactory.create(
                "ssm", "ssm-commands.json", new TypeReference<>() {});
        AccountAwareStorageBackend<CommandInvocation> invocations = storageFactory.create(
                "ssm", "ssm-invocations.json", new TypeReference<>() {});
        String key = region + "::" + commandId;
        assertTrue(commands.getForAccount(account, key).isPresent());
        assertTrue(invocations.getForAccount(account, key + "::" + instance).isPresent());
    }

    private void deleteCommand(String account, String region, String commandId, String instance) {
        AccountAwareStorageBackend<Command> commands = storageFactory.create(
                "ssm", "ssm-commands.json", new TypeReference<>() {});
        AccountAwareStorageBackend<CommandInvocation> invocations = storageFactory.create(
                "ssm", "ssm-invocations.json", new TypeReference<>() {});
        String key = region + "::" + commandId;
        assertAll("remove owned queue, command and invocation",
                () -> RequestScopes.runAs(account, region, () ->
                        commandService.failActiveInvocationsForInstance(region, instance, "fixture cleanup")),
                () -> {
                    commands.deleteForAccount(account, key);
                    assertTrue(commands.getForAccount(account, key).isEmpty());
                },
                () -> {
                    invocations.deleteForAccount(account, key + "::" + instance);
                    assertTrue(invocations.getForAccount(account, key + "::" + instance).isEmpty());
                });
    }

    private void joinWorker(AtomicReference<Thread> worker) throws InterruptedException {
        if (worker.get() != null) {
            worker.get().join(5000);
            assertFalse(worker.get().isAlive(), "Owned direct worker did not finish");
        }
    }

    private Response call(String account, String region, String action, Map<String, Object> body) {
        return given().port(endpoint.getPort()).contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261013/" + region + "/ssm/aws4_request")
                .header("X-Amz-Target", action).body(body).post("/")
                .then().statusCode(200).extract().response();
    }
}
