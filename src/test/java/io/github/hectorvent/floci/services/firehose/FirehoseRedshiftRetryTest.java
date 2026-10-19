package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.firehose.model.Record;
import io.github.hectorvent.floci.services.firehose.model.RedshiftDestination;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirehoseRedshiftRetryTest {

    private static final String STREAM = "rs-retry";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private FirehoseService service;
    private S3Service s3Service;
    private FirehoseRedshiftDelivery delivery;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Map<String, AccountAwareStorageBackend<?>> backends = new HashMap<>();
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> backends.computeIfAbsent(
                        invocation.getArgument(0) + "/" + invocation.getArgument(1),
                        k -> AccountAwareStorageBackend.inMemory("000000000000")));
        s3Service = Mockito.mock(S3Service.class);
        delivery = Mockito.mock(FirehoseRedshiftDelivery.class);
        clock = new MutableClock();

        EmulatorConfig.FirehoseServiceConfig firehoseCfg = mock(EmulatorConfig.FirehoseServiceConfig.class);
        when(firehoseCfg.enabled()).thenReturn(true);
        when(firehoseCfg.tickIntervalSeconds()).thenReturn(10L);
        when(firehoseCfg.flushRecordCount()).thenReturn(0);
        EmulatorConfig.ServicesConfig servicesCfg = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesCfg.firehose()).thenReturn(firehoseCfg);
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(servicesCfg);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");

        service = new FirehoseService(storageFactory, s3Service, new KinesisService(storageFactory, regionResolver),
                regionResolver, clock, config, mock(FirehoseParquetConverter.class),
                mock(FirehoseLambdaTransformer.class), delivery);
    }

    private void createStream(String extraMembers) throws Exception {
        RedshiftDestination destination = mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events"},
                 "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"}
                 %s}
                """.formatted(extraMembers), RedshiftDestination.class);
        service.createDeliveryStream("us-east-1", STREAM, null, destination, List.of(), null, null);
        service.putRecord(STREAM, new Record("{\"id\":1}".getBytes(StandardCharsets.UTF_8)));
    }

    private void copyFails() {
        doThrow(new IllegalStateException("relation does not exist"))
                .when(delivery).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    private void verifyStagedTimes(int times) {
        verify(s3Service, times(times)).putObject(eq("stage"), anyString(), any(byte[].class), anyString(),
                anyMap(), any());
    }

    @Test
    void successfulCopyConsumesTheBatchOnce() throws Exception {
        createStream("");

        service.flush(STREAM);
        service.flush(STREAM);
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(eq("000000000000"), eq("us-east-1"), any(), eq("stage"), anyString());
    }

    @Test
    void failedCopyIsRetriedAgainstTheSameStagedObjectWithoutRestaging() throws Exception {
        createStream("");
        copyFails();

        service.flush(STREAM);
        service.retryPendingRedshiftCopies();
        service.retryPendingRedshiftCopies();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(delivery, times(3)).copy(anyString(), anyString(), any(), eq("stage"), keys.capture());
        assertEquals(1, new HashSet<>(keys.getAllValues()).size(), "every attempt must use the one staged key");
        verifyStagedTimes(1);
    }

    @Test
    void recoveredCopyStopsBeingRetried() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);
        Mockito.reset(delivery);

        service.retryPendingRedshiftCopies();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void failedCopyIsDroppedAfterTheRetryWindowButTheStagedObjectStays() throws Exception {
        createStream(", \"RetryOptions\": {\"DurationInSeconds\": 10}");
        copyFails();

        service.flush(STREAM);
        clock.advance(Duration.ofSeconds(11));
        service.retryPendingRedshiftCopies();
        service.retryPendingRedshiftCopies();

        verify(delivery, times(2)).copy(anyString(), anyString(), any(), anyString(), anyString());
        verifyStagedTimes(1);
    }

    @Test
    void zeroRetryDurationMeansASingleAttempt() throws Exception {
        createStream(", \"RetryOptions\": {\"DurationInSeconds\": 0}");
        copyFails();

        service.flush(STREAM);
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void anOlderPendingBatchIsLoadedBeforeANewerOne() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);
        service.putRecord(STREAM, new Record("{\"id\":2}".getBytes(StandardCharsets.UTF_8)));

        service.flush(STREAM);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(delivery, times(2)).copy(anyString(), anyString(), any(), eq("stage"), keys.capture());
        assertEquals(1, new HashSet<>(keys.getAllValues()).size(),
                "the second batch must wait behind the failing first one");
    }

    @Test
    void deletingTheStreamDiscardsPendingCopies() throws Exception {
        createStream("");
        copyFails();
        service.flush(STREAM);

        service.deleteDeliveryStream(STREAM);
        service.retryPendingRedshiftCopies();

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void backupEnabledWritesTheBatchToTheBackupBucketOnlyOnce() throws Exception {
        createStream(", \"S3BackupMode\": \"Enabled\", \"S3BackupConfiguration\": "
                + "{\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\", \"BucketARN\": \"arn:aws:s3:::bk\"}");
        copyFails();

        service.flush(STREAM);
        service.retryPendingRedshiftCopies();

        verifyStagedTimes(1);
        verify(s3Service, times(1)).putObject(eq("bk"), anyString(), any(byte[].class), anyString(), anyMap(), any());
    }
}
