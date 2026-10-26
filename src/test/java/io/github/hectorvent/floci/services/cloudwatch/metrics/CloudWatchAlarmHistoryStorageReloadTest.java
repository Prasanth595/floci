package io.github.hectorvent.floci.services.cloudwatch.metrics;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServiceConfigAccess;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.AlarmHistoryItem;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricAlarm;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CloudWatchAlarmHistoryStorageReloadTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "eu-west-1";

    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void historyOfADeletedAlarmSurvivesReloadAndKeepsAppendingAndPaging(String mode) {
        StorageFactory first = factory(mode);
        MetricAlarm alarm = new MetricAlarm();
        alarm.setAlarmName("reloaded");
        List<AlarmHistoryItem> before;
        try {
            CloudWatchMetricsService service = new CloudWatchMetricsService(first, new RegionResolver(REGION, ACCOUNT));
            service.putMetricAlarm(alarm, REGION);
            service.setAlarmState("reloaded", "ALARM", "breached", "{\"x\":1}", REGION);
            service.deleteAlarms(List.of("reloaded"), REGION);
            before = service.describeAlarmHistory("reloaded", null, null, null, null, null, null, null, REGION).items();
        } finally {
            first.shutdownAll();
        }
        assertEquals(3, before.size());

        StorageFactory second = factory(mode);
        List<AlarmHistoryItem> pages = new ArrayList<>();
        try {
            CloudWatchMetricsService service = new CloudWatchMetricsService(second, new RegionResolver(REGION, ACCOUNT));
            service.putMetricAlarm(alarm, REGION);
            String token = null;
            do {
                PaginatedResult<AlarmHistoryItem> page = service.describeAlarmHistory(
                        "reloaded", null, null, null, null, 2, token, null, REGION);
                pages.addAll(page.items());
                token = page.nextToken();
            } while (token != null);
        } finally {
            second.shutdownAll();
        }

        assertEquals(4, pages.size());
        assertEquals("Alarm \"reloaded\" created", pages.getFirst().historySummary());
        assertEquals(before, pages.subList(1, 4));
    }

    private StorageFactory factory(String mode) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.storage().persistentPath()).thenReturn(directory.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(3_600_000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode(anyString())).thenReturn(mode);
        when(access.storageFlushInterval(anyString())).thenReturn(3_600_000L);
        return new StorageFactory(config, access);
    }
}
