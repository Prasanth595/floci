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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirehoseRedshiftServiceTest {

    private static final String STREAM = "rs-delivery";
    private static final String BACKUP_ENABLED = ", \"S3BackupMode\": \"Enabled\", \"S3BackupConfiguration\": "
            + "{\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\", \"BucketARN\": \"arn:aws:s3:::bk\"}";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private FirehoseService service;
    private S3Service s3Service;
    private FirehoseRedshiftDelivery delivery;
    private MutableClock clock;
    private StorageFactory storageFactory;
    private RegionResolver regionResolver;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        storageFactory = Mockito.mock(StorageFactory.class);
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
        config = mock(EmulatorConfig.class);
        when(config.services()).thenReturn(servicesCfg);
        regionResolver = new RegionResolver("us-east-1", "000000000000");

        service = new FirehoseService(storageFactory, s3Service, new KinesisService(storageFactory, regionResolver),
                regionResolver, clock, config, mock(FirehoseParquetConverter.class),
                mock(FirehoseLambdaTransformer.class), delivery, Runnable::run);
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

        verify(delivery, times(1)).copy(eq("000000000000"), eq("us-east-1"), any(), eq("stage"), anyString());
        verifyStagedTimes(1);
    }

    @Test
    void failedCopyIsLoggedAndNotRestagedOrRebuffered() throws Exception {
        createStream("");
        copyFails();

        service.flush(STREAM);
        service.flush(STREAM);

        verify(delivery, times(1)).copy(anyString(), anyString(), any(), eq("stage"), anyString());
        verifyStagedTimes(1);
    }

    @Test
    void deliveryUnavailableLeavesStagedObjectInS3() throws Exception {
        FirehoseService serviceWithoutDelivery = new FirehoseService(storageFactory, s3Service,
                new KinesisService(storageFactory, regionResolver), regionResolver, clock, config,
                mock(FirehoseParquetConverter.class), mock(FirehoseLambdaTransformer.class), null, Runnable::run);
        RedshiftDestination destination = mapper.readValue("""
                {"RoleARN": "arn:aws:iam::000000000000:role/firehose",
                 "ClusterJDBCURL": "jdbc:redshift://c1.abc.us-east-1.redshift.amazonaws.com:5439/dev",
                 "Username": "admin", "Password": "Secret123",
                 "CopyCommand": {"DataTableName": "events"},
                 "S3Configuration": {"RoleARN": "arn:aws:iam::000000000000:role/firehose", "BucketARN": "arn:aws:s3:::stage"}}
                """, RedshiftDestination.class);
        serviceWithoutDelivery.createDeliveryStream("us-east-1", "fh-no-delivery", null, destination, List.of(), null, null);
        serviceWithoutDelivery.putRecord("fh-no-delivery", new Record("{\"id\":1}".getBytes(StandardCharsets.UTF_8)));

        serviceWithoutDelivery.flush("fh-no-delivery");

        verifyStagedTimes(1);
        verify(delivery, never()).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void backupEnabledWritesTheBatchToTheBackupBucket() throws Exception {
        createStream(BACKUP_ENABLED);

        service.flush(STREAM);

        verifyStagedTimes(1);
        verify(s3Service, times(1)).putObject(eq("bk"), anyString(), any(byte[].class), anyString(), anyMap(), any());
        verify(delivery, times(1)).copy(anyString(), anyString(), any(), eq("stage"), anyString());
    }

    @Test
    void backupObjectUsesTheBackupConfigurationPrefix() throws Exception {
        createStream(", \"S3BackupMode\": \"Enabled\", \"S3BackupConfiguration\": "
                + "{\"RoleARN\": \"arn:aws:iam::000000000000:role/firehose\", \"BucketARN\": \"arn:aws:s3:::bk\","
                + " \"Prefix\": \"backup/\"}");

        service.flush(STREAM);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(s3Service).putObject(eq("bk"), keys.capture(), any(byte[].class), anyString(), anyMap(), any());
        assertTrue(keys.getValue().startsWith("backup/"), keys.getValue());
    }

    @Test
    void aFailingBackupWriteStillLoadsTheStagedBatchAndDoesNotRestageIt() throws Exception {
        createStream(BACKUP_ENABLED);
        doThrow(new IllegalStateException("backup bucket unavailable")).when(s3Service)
                .putObject(eq("bk"), anyString(), any(byte[].class), anyString(), anyMap(), any());

        service.flush(STREAM);
        service.flush(STREAM);

        verifyStagedTimes(1);
        verify(delivery, times(1)).copy(anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void aFailingPrimaryWriteWritesNoBackupSoNothingIsDelivered() throws Exception {
        createStream(BACKUP_ENABLED);
        doThrow(new IllegalStateException("s3 unavailable")).when(s3Service)
                .putObject(eq("stage"), anyString(), any(byte[].class), anyString(), anyMap(), any());

        service.flush(STREAM);

        verify(s3Service, never()).putObject(eq("bk"), anyString(), any(byte[].class), anyString(), anyMap(), any());
        verify(delivery, never()).copy(anyString(), anyString(), any(), anyString(), anyString());
    }
}
