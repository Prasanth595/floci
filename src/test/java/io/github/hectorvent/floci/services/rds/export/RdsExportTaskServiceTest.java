package io.github.hectorvent.floci.services.rds.export;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.ExportTask;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The export task lifecycle, with the export itself replaced: the statuses a task passes
 * through, cancellation, the request checks and the recovery of tasks a restart interrupted.
 */
class RdsExportTaskServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String SNAPSHOT_ARN = "arn:aws:rds:us-east-1:000000000000:snapshot:appdb-snap";
    private static final Instant SNAPSHOT_TIME = Instant.parse("2026-10-13T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T09:00:00Z"), ZoneOffset.UTC);

    private RdsService rdsService;
    private S3Service s3Service;
    private RegionResolver regionResolver;
    private final List<Runnable> workers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rdsService = mock(RdsService.class);
        s3Service = mock(S3Service.class);
        regionResolver = new RegionResolver(REGION, ACCOUNT);
        when(rdsService.snapshotExportSource(SNAPSHOT_ARN, REGION)).thenReturn(new SnapshotExportSource(
                SNAPSHOT_ARN, "postgres:16-alpine", "master", "secret", SNAPSHOT_TIME, "-- dump"));
        when(s3Service.bucketExists("exports")).thenReturn(true);
    }

    private RdsExportTaskService service(SnapshotExporter exporter) {
        return new RdsExportTaskService(new InMemoryStorage<>(), rdsService, s3Service, exporter,
                regionResolver, false, CLOCK, workers::add);
    }

    private ExportTask start(RdsExportTaskService service, String id, List<String> exportOnly) {
        return service.startExportTask(id, SNAPSHOT_ARN, "exports", "prefix", "arn:aws:iam::000000000000:role/export",
                "alias/export", exportOnly, REGION);
    }

    private ExportTask describe(RdsExportTaskService service, String id) {
        return service.describeExportTasks(id, null, null, Map.of(), REGION).getFirst();
    }

    private void runWorkers() {
        List<Runnable> pending = List.copyOf(workers);
        workers.clear();
        pending.forEach(Runnable::run);
    }

    @Test
    void startExportTask_isStartingWithTheSnapshotAndRequestRecorded() {
        RdsExportTaskService service = service((task, source, progress) -> new SnapshotExporter.Outcome(0, List.of()));

        ExportTask task = start(service, "appdb-export", List.of("appdb.public.items"));

        assertThat(task.getStatus(), equalTo("STARTING"));
        assertThat(task.getSourceArn(), equalTo(SNAPSHOT_ARN));
        assertThat(task.getSourceType(), equalTo("SNAPSHOT"));
        assertThat(task.getSnapshotTime(), equalTo(SNAPSHOT_TIME));
        assertThat(task.getTaskStartTime(), equalTo(CLOCK.instant()));
        assertThat(task.getExportOnly(), contains("appdb.public.items"));
        assertThat(task.getIamRoleArn(), equalTo("arn:aws:iam::000000000000:role/export"));
        assertThat(task.getKmsKeyId(), equalTo("alias/export"));
        assertThat(task.getPercentProgress(), equalTo(0));
    }

    @Test
    void aTaskMovesThroughInProgressToComplete() {
        List<String> seen = new ArrayList<>();
        RdsExportTaskService[] holder = new RdsExportTaskService[1];
        holder[0] = service((task, source, progress) -> {
            progress.extracting(50);
            seen.add(describe(holder[0], task.getExportTaskIdentifier()).getStatus()
                    + "@" + describe(holder[0], task.getExportTaskIdentifier()).getPercentProgress());
            return new SnapshotExporter.Outcome(2_147_483_648L, List.of());
        });
        start(holder[0], "appdb-export", List.of());

        runWorkers();

        ExportTask done = describe(holder[0], "appdb-export");
        assertThat(seen, contains("IN_PROGRESS@50"));
        assertThat(done.getStatus(), equalTo("COMPLETE"));
        assertThat(done.getPercentProgress(), equalTo(100));
        assertThat(done.getTotalExtractedDataInGb(), equalTo(2));
        assertThat(done.getTaskEndTime(), equalTo(CLOCK.instant()));
        assertThat(done.getFailureCause(), nullValue());
    }

    @Test
    void aFailedExportReportsItsCause() {
        RdsExportTaskService service = service((task, source, progress) -> {
            throw new IllegalStateException("psql failed with exit code 1: relation does not exist");
        });
        start(service, "appdb-export", List.of());

        runWorkers();

        ExportTask failed = describe(service, "appdb-export");
        assertThat(failed.getStatus(), equalTo("FAILED"));
        assertThat(failed.getFailureCause(), equalTo("psql failed with exit code 1: relation does not exist"));
        assertThat(failed.getTaskEndTime(), notNullValue());
    }

    @Test
    void skippedTablesBecomeTheWarningMessage() {
        RdsExportTaskService service = service((task, source, progress) ->
                new SnapshotExporter.Outcome(10, List.of("The table appdb.public.odd was skipped.")));
        start(service, "appdb-export", List.of());

        runWorkers();

        assertThat(describe(service, "appdb-export").getWarningMessage(),
                equalTo("The table appdb.public.odd was skipped."));
    }

    @Test
    void cancelingStopsTheExportAndEndsCanceled() {
        List<Boolean> canceledSeen = new ArrayList<>();
        RdsExportTaskService[] holder = new RdsExportTaskService[1];
        holder[0] = service((task, source, progress) -> {
            progress.extracting(10);
            holder[0].cancelExportTask(task.getExportTaskIdentifier(), REGION);
            assertThat(describe(holder[0], task.getExportTaskIdentifier()).getStatus(), equalTo("CANCELING"));
            progress.extracting(60);
            canceledSeen.add(progress.canceled());
            return new SnapshotExporter.Outcome(0, List.of());
        });
        start(holder[0], "appdb-export", List.of());

        runWorkers();

        ExportTask canceled = describe(holder[0], "appdb-export");
        assertThat(canceledSeen, contains(true));
        assertThat(canceled.getStatus(), equalTo("CANCELED"));
        assertThat(canceled.getPercentProgress(), equalTo(10));
    }

    @Test
    void aFinishedTaskCannotBeCanceled() {
        RdsExportTaskService service = service((task, source, progress) -> new SnapshotExporter.Outcome(0, List.of()));
        start(service, "appdb-export", List.of());
        runWorkers();

        AwsException error = assertThrows(AwsException.class,
                () -> service.cancelExportTask("appdb-export", REGION));

        assertThat(error.getErrorCode(), equalTo("InvalidExportTaskStateFault"));
    }

    @Test
    void anIdentifierCanOnlyBeUsedOnce() {
        RdsExportTaskService service = service((task, source, progress) -> new SnapshotExporter.Outcome(0, List.of()));
        start(service, "appdb-export", List.of());

        AwsException error = assertThrows(AwsException.class, () -> start(service, "appdb-export", List.of()));

        assertThat(error.getErrorCode(), equalTo("ExportTaskAlreadyExists"));
    }

    @Test
    void aMissingBucketIsRefusedBeforeAnythingRuns() {
        when(s3Service.bucketExists("exports")).thenReturn(false);
        RdsExportTaskService service = service((task, source, progress) -> new SnapshotExporter.Outcome(0, List.of()));

        AwsException error = assertThrows(AwsException.class, () -> start(service, "appdb-export", List.of()));

        assertThat(error.getErrorCode(), equalTo("InvalidS3BucketFault"));
        assertThat(workers, empty());
    }

    @Test
    void identifiersFollowTheRdsRules() {
        RdsExportTaskService service = service((task, source, progress) -> new SnapshotExporter.Outcome(0, List.of()));

        for (String invalid : List.of("1export", "export--task", "export-", "export_task", "e".repeat(61))) {
            AwsException error = assertThrows(AwsException.class, () -> start(service, invalid, List.of()));
            assertThat(invalid, error.getErrorCode(), equalTo("InvalidParameterValue"));
        }
        verify(rdsService, never()).snapshotExportSource(anyString(), any());
    }

    @Test
    void anUnknownTaskIsNotFound() {
        RdsExportTaskService service = service((task, source, progress) -> new SnapshotExporter.Outcome(0, List.of()));

        AwsException describe = assertThrows(AwsException.class,
                () -> service.describeExportTasks("missing", null, null, Map.of(), REGION));
        AwsException cancel = assertThrows(AwsException.class, () -> service.cancelExportTask("missing", REGION));

        assertThat(describe.getErrorCode(), equalTo("ExportTaskNotFound"));
        assertThat(describe.getHttpStatus(), equalTo(404));
        assertThat(cancel.getErrorCode(), equalTo("ExportTaskNotFound"));
    }

    @Test
    void describeFiltersByStatusBucketAndSource() {
        RdsExportTaskService service = service((task, source, progress) -> new SnapshotExporter.Outcome(0, List.of()));
        start(service, "first", List.of());
        runWorkers();
        start(service, "second", List.of());

        assertThat(service.describeExportTasks(null, null, null, Map.of("status", List.of("complete")), REGION)
                .stream().map(ExportTask::getExportTaskIdentifier).toList(), contains("first"));
        assertThat(service.describeExportTasks(null, null, null, Map.of("status", List.of("starting")), REGION)
                .stream().map(ExportTask::getExportTaskIdentifier).toList(), contains("second"));
        assertThat(service.describeExportTasks(null, SNAPSHOT_ARN, "SNAPSHOT",
                Map.of("s3-bucket", List.of("exports")), REGION), hasSize(2));
        assertThat(service.describeExportTasks(null, null, "CLUSTER", Map.of(), REGION), empty());
        AwsException unknown = assertThrows(AwsException.class, () ->
                service.describeExportTasks(null, null, null, Map.of("engine", List.of("postgres")), REGION));
        assertThat(unknown.getErrorCode(), equalTo("InvalidParameterValue"));
    }

    @Test
    void mockModeCompletesWithoutExporting() {
        SnapshotExporter exporter = mock(SnapshotExporter.class);
        RdsExportTaskService service = new RdsExportTaskService(new InMemoryStorage<>(), rdsService, s3Service,
                exporter, regionResolver, true, CLOCK, workers::add);
        start(service, "appdb-export", List.of());

        runWorkers();

        assertThat(describe(service, "appdb-export").getStatus(), equalTo("COMPLETE"));
        verify(exporter, never()).export(any(), any(), any());
    }

    @Test
    void aTaskARestartInterruptedIsFailed() {
        AccountAwareStorageBackend<ExportTask> persisted =
                new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, ACCOUNT);
        ExportTask running = new ExportTask();
        running.setExportTaskIdentifier("appdb-export");
        running.setStatus(ExportTask.STATUS_IN_PROGRESS);
        running.setTaskStartTime(SNAPSHOT_TIME);
        persisted.putForAccount(ACCOUNT, "export-task::us-east-1::appdb-export", running);

        SnapshotExporter exporter = mock(SnapshotExporter.class);

        RdsExportTaskService service = new RdsExportTaskService(persisted, rdsService, s3Service, exporter,
                regionResolver, false, CLOCK, workers::add);

        ExportTask recovered = describe(service, "appdb-export");
        assertThat(recovered.getStatus(), equalTo("FAILED"));
        assertThat(recovered.getFailureCause(), equalTo(RdsExportTaskService.RESTART_FAILURE_CAUSE));
        verify(exporter).discard("appdb-export");
    }
}
