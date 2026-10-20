package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.BackupJob;
import io.github.hectorvent.floci.services.backup.model.BackupVault;
import io.github.hectorvent.floci.services.backup.model.RecoveryPoint;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupReviewRegressionTest {
    private static final String ACCOUNT = "123456789012";
    private static final String REGION = "eu-west-1";
    private static final String VAULT = "review";

    @Test
    void deletionWaitsForCompletionMonitorAndLeavesCountConsistent() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BackupJob first = fixture.startRunning();
            fixture.complete(first);
            BackupJob second = fixture.startRunning();
            Object monitor = fixture.vaultMonitor();
            CountDownLatch entered = new CountDownLatch(1);
            FutureTask<Void> deletion = new FutureTask<>(() -> {
                entered.countDown();
                fixture.service.deleteRecoveryPoint(VAULT, first.getRecoveryPointArn(), REGION);
                return null;
            });
            Thread worker = new Thread(deletion, "backup-review-delete");
            boolean waited;
            try {
                synchronized (monitor) {
                    worker.start();
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    await().atMost(Duration.ofSeconds(5)).until(() ->
                            worker.getState() == Thread.State.BLOCKED || deletion.isDone());
                    waited = worker.getState() == Thread.State.BLOCKED && !deletion.isDone();
                    // Completion is reentrant on this monitor, while deletion must wait outside it.
                    fixture.complete(second);
                }
                deletion.get(5, TimeUnit.SECONDS);
                assertAll(
                        () -> assertTrue(waited, "Point deletion must wait on completion's vault monitor"),
                        () -> assertEquals(1, fixture.service.listRecoveryPointsByBackupVault(VAULT, REGION).size()),
                        () -> assertEquals(1, fixture.vault().getNumberOfRecoveryPoints()),
                        () -> assertThrows(AwsException.class,
                                () -> fixture.service.deleteBackupVault(VAULT, REGION)));
                fixture.service.deleteRecoveryPoint(VAULT, second.getRecoveryPointArn(), REGION);
                assertTrue(fixture.service.listRecoveryPointsByBackupVault(VAULT, REGION).isEmpty());
                assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
                fixture.service.deleteBackupVault(VAULT, REGION);
                assertThrows(AwsException.class, fixture::vault);
            } finally {
                worker.join(5000);
                assertFalse(worker.isAlive(), "Deletion worker must be cleaned up");
            }
        }
    }

    @Test
    void retentionReadPointDeleteAndCountReadWriteAllHoldCompletionMonitor() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BackupJob job = fixture.startRunning();
            fixture.complete(job);
            Object monitor = fixture.vaultMonitor();
            RecoveryPoint point = fixture.points.get(job.getRecoveryPointArn()).orElseThrow();
            fixture.service.putBackupVaultLockConfiguration(VAULT, REGION, 1L, null, null);
            doAnswer(call -> {
                assertTrue(Thread.holdsLock(monitor), "Retention and count reads must hold the vault monitor");
                return call.callRealMethod();
            }).when(fixture.vaults).get(REGION + ":" + VAULT);
            doAnswer(call -> {
                assertTrue(Thread.holdsLock(monitor), "Point lookup for retention must hold the vault monitor");
                return call.callRealMethod();
            }).when(fixture.points).get(job.getRecoveryPointArn());
            doAnswer(call -> {
                assertTrue(Thread.holdsLock(monitor), "Point deletion must hold the vault monitor");
                return call.callRealMethod();
            }).when(fixture.points).delete(job.getRecoveryPointArn());
            doAnswer(call -> {
                assertTrue(Thread.holdsLock(monitor), "Count update must hold the vault monitor");
                return call.callRealMethod();
            }).when(fixture.vaults).put(eq(REGION + ":" + VAULT), any());

            AwsException protectedPoint = assertThrows(AwsException.class,
                    () -> fixture.service.deleteRecoveryPoint(VAULT, job.getRecoveryPointArn(), REGION));
            assertEquals("InvalidRequestException", protectedPoint.getErrorCode());
            verify(fixture.points, never()).delete(anyString());
            point.setCreationDate(Instant.now().minus(Duration.ofDays(2)).getEpochSecond());
            fixture.service.deleteRecoveryPoint(VAULT, job.getRecoveryPointArn(), REGION);
            verify(fixture.points).delete(job.getRecoveryPointArn());
            // Stop monitoring before the public, unlocked describe used only for assertions.
            doCallRealMethod().when(fixture.vaults).get(anyString());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
            fixture.service.deleteBackupVault(VAULT, REGION);
        }
    }

    @Test
    void brandNewPointIncrementsCountWithoutScanningRecoveryStore() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BackupJob first = fixture.startRunning();
            fixture.complete(first);
            BackupJob second = fixture.startRunning();
            fixture.complete(second);
            verify(fixture.points, never()).scan(any());
            assertEquals(2, fixture.vault().getNumberOfRecoveryPoints());
            assertEquals(2, fixture.points.keys().size());
            fixture.complete(first);
            assertEquals(2, fixture.vault().getNumberOfRecoveryPoints());
            verify(fixture.points, never()).scan(any());
        }
    }

    @Test
    void stablePointRetryRecountsAndRestartDoesNotDuplicateIt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            BackupJob job = fixture.startRunning();
            fixture.complete(job);
            String pointArn = job.getRecoveryPointArn();
            RecoveryPoint original = fixture.points.get(pointArn).orElseThrow();
            original.setCreationDate(42L);
            fixture.vault().setNumberOfRecoveryPoints(9);
            job.setState("RUNNING");
            job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
            fixture.jobs.put(job.getBackupJobId(), job);
            fixture.service.shutdown();
            clearInvocations(fixture.points);
            fixture.service = fixture.newService();
            fixture.service.resumeJobs();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("COMPLETED", fixture.service.describeBackupJob(job.getBackupJobId()).getState()));
            verify(fixture.points, atLeastOnce()).scan(any());
            assertEquals(1, fixture.points.keys().size());
            assertEquals(1, fixture.vault().getNumberOfRecoveryPoints());
            assertEquals(42L, fixture.points.get(pointArn).orElseThrow().getCreationDate());
            assertEquals(pointArn, fixture.service.describeBackupJob(job.getBackupJobId()).getRecoveryPointArn());
            fixture.service.resumeJobs();
            fixture.complete(job);
            assertEquals(1, fixture.points.keys().size());
            assertEquals(1, fixture.vault().getNumberOfRecoveryPoints());
        }
    }

    @Test
    void newJobPersistsVaultIncarnationWithoutNewAccessorDependency() {
        try (Fixture fixture = new Fixture()) {
            fixture.vault().setCreationDate(100L);
            BackupJob job = fixture.startRunning();
            assertEquals(100L, new ObjectMapper().valueToTree(fixture.jobs.get(job.getBackupJobId()).orElseThrow())
                    .path("BackupVaultCreationDate").asLong(-1), "New jobs must persist the captured incarnation");
        }
    }

    @Test
    void capturedIncarnationRejectsNewerSameArnReplacement() throws Exception {
        assertReplacementRejected(200L);
    }

    @Test
    void capturedIncarnationRequiresEqualityEvenForOlderSameArnReplacement() throws Exception {
        assertReplacementRejected(99L);
    }

    private void assertReplacementRejected(long replacementDate) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.vault().setCreationDate(100L);
            BackupJob job = fixture.startRunning();
            String originalArn = fixture.vault().getBackupVaultArn();
            fixture.service.deleteBackupVault(VAULT, REGION);
            BackupVault replacement = fixture.service.createBackupVault(VAULT, null, null, Map.of(), REGION);
            replacement.setCreationDate(replacementDate);
            assertEquals(originalArn, replacement.getBackupVaultArn());
            fixture.complete(job);
            assertEquals("FAILED", fixture.service.describeBackupJob(job.getBackupJobId()).getState());
            assertTrue(fixture.points.keys().isEmpty());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
            fixture.service.deleteBackupVault(VAULT, REGION);
        }
    }

    @Test
    void legacyAbsentIdentityAllowsOlderAndSameSecondVaults() throws Exception {
        // Epoch-second identity cannot distinguish a delete/recreate within the same second.
        for (long vaultDate : new long[]{99L, 100L}) {
            try (Fixture fixture = new Fixture()) {
                fixture.vault().setCreationDate(vaultDate);
                BackupJob legacy = fixture.legacy("RUNNING", 100L);
                fixture.complete(legacy);
                assertEquals("COMPLETED", fixture.service.describeBackupJob(legacy.getBackupJobId()).getState());
                assertEquals(1, fixture.vault().getNumberOfRecoveryPoints());
                assertEquals("arn:aws:backup:" + REGION + ":" + ACCOUNT + ":recovery-point:legacy",
                        legacy.getRecoveryPointArn());
                assertEquals(ACCOUNT, legacy.getAccountId());
            }
        }
    }

    @Test
    void legacyAbsentIdentityRejectsVaultNewerThanJob() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.vault().setCreationDate(101L);
            BackupJob legacy = fixture.legacy("RUNNING", 100L);
            fixture.complete(legacy);
            assertEquals("FAILED", fixture.service.describeBackupJob(legacy.getBackupJobId()).getState());
            assertTrue(fixture.points.keys().isEmpty());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
        }
    }

    @Test
    void restartKeepsLegacyStartAndCompletionDeadlinesInOriginalRegion() {
        try (Fixture fixture = new Fixture()) {
            long now = Instant.now().getEpochSecond();
            fixture.vault().setCreationDate(now - 20);
            BackupJob expired = fixture.legacy("CREATED", now - 10);
            expired.setStartBy(now - 1);
            BackupJob future = fixture.legacy("RUNNING", now - 10);
            future.setBackupJobId("future");
            future.setExpectedCompletionDate(now + 3600);
            fixture.jobs.put("future", future);
            fixture.jobs.put("legacy", expired);
            fixture.service.resumeJobs();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertEquals("EXPIRED", fixture.service.describeBackupJob("legacy").getState()));
            assertEquals("RUNNING", fixture.service.describeBackupJob("future").getState());
            assertEquals(now + 3600, fixture.service.describeBackupJob("future").getExpectedCompletionDate());
            assertTrue(fixture.points.keys().isEmpty());
            assertEquals(0, fixture.vault().getNumberOfRecoveryPoints());
        }
    }

    private static class Fixture implements AutoCloseable {
        private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        private final StorageFactory storage = mock(StorageFactory.class);
        private final EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        private final RegionResolver resolver = new RegionResolver("us-east-1", ACCOUNT);
        private final AccountAwareStorageBackend<BackupVault> vaults;
        private final AccountAwareStorageBackend<BackupJob> jobs;
        private final AccountAwareStorageBackend<RecoveryPoint> points;
        private BackupService service;

        Fixture() {
            when(storage.create(eq("backup"), anyString(), any())).thenAnswer(call ->
                    stores.computeIfAbsent(call.getArgument(1), ignored ->
                            spy(AccountAwareStorageBackend.inMemory(ACCOUNT))));
            when(config.services().backup().jobCompletionDelaySeconds()).thenReturn(3600);
            service = newService();
            vaults = store("backup-vaults.json");
            jobs = store("backup-jobs.json");
            points = store("backup-recovery-points.json");
            service.createBackupVault(VAULT, null, null, Map.of(), REGION);
        }

        @SuppressWarnings("unchecked")
        private <T> AccountAwareStorageBackend<T> store(String name) {
            return (AccountAwareStorageBackend<T>) stores.get(name);
        }

        private BackupService newService() {
            return new BackupService(storage, config, resolver, new ObjectMapper());
        }

        private BackupVault vault() {
            return service.describeBackupVault(VAULT, REGION);
        }

        private BackupJob startRunning() {
            BackupJob job = service.startBackupJob(VAULT, "arn:aws:s3:::review-bucket", "role", null, REGION);
            job.setState("RUNNING");
            jobs.put(job.getBackupJobId(), job);
            return job;
        }

        private BackupJob legacy(String state, long creationDate) {
            BackupJob job = new BackupJob();
            job.setBackupJobId("legacy");
            job.setBackupVaultName(VAULT);
            job.setBackupVaultArn(vault().getBackupVaultArn());
            job.setResourceArn("arn:aws:s3:::review-bucket");
            job.setAccountId(ACCOUNT);
            job.setState(state);
            job.setCreationDate(creationDate);
            job.setStartBy(Instant.now().getEpochSecond() + 3600);
            job.setExpectedCompletionDate(Instant.now().getEpochSecond() - 1);
            jobs.put(job.getBackupJobId(), job);
            return job;
        }

        private void complete(BackupJob job) throws Exception {
            // Resolver and all stores share ACCOUNT; REGION is deliberately not the resolver default.
            Method method = BackupService.class.getDeclaredMethod("completeJob", String.class, String.class, String.class);
            method.setAccessible(true);
            method.invoke(service, job.getBackupJobId(), VAULT, REGION);
        }

        private Object vaultMonitor() throws Exception {
            Field field = BackupService.class.getDeclaredField("vaultLocks");
            field.setAccessible(true);
            Map<?, ?> locks = (Map<?, ?>) field.get(service);
            return locks.entrySet().stream()
                    .filter(entry -> entry.getKey().toString().endsWith(REGION + ":" + VAULT))
                    .map(Map.Entry::getValue).findFirst().orElseThrow();
        }

        @Override
        public void close() {
            service.shutdown();
        }
    }
}
