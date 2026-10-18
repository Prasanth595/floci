package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LambdaWebLogsTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String STREAM = "2026/10/18/web[rev-abcde]0123456789abcdef0123456789abcdef";

    @Mock
    CloudWatchLogsService cloudWatchLogs;

    private LambdaWebLogs logs;

    @AfterEach
    void stop() {
        if (logs != null) {
            logs.shutdown();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventsGoToTheLocalGroupAndTheCentralizedGroupCreatedOnFirstUse() {
        logs = new LambdaWebLogs(cloudWatchLogs);
        LambdaWebLogs.Sink sink = logs.open(ACCOUNT, REGION, "/aws/lambda/web/web", STREAM);

        sink.accept("first\n");

        ArgumentCaptor<List<Map<String, Object>>> local = ArgumentCaptor.forClass(List.class);
        verify(cloudWatchLogs, timeout(5_000)).putLogEventsForAccount(eq(ACCOUNT), eq("/aws/lambda/web/web.local"),
                eq(STREAM), local.capture(), eq(REGION));
        verify(cloudWatchLogs, timeout(5_000)).putLogEventsForAccount(eq(ACCOUNT), eq("/aws/lambda/web/web"),
                eq(STREAM + "-" + ACCOUNT + "-" + REGION), anyList(), eq(REGION));
        verify(cloudWatchLogs).createLogGroupForAccount(ACCOUNT, "/aws/lambda/web/web.local", null, null, REGION);
        verify(cloudWatchLogs).createLogStreamForAccount(ACCOUNT, "/aws/lambda/web/web.local", STREAM, REGION);
        verify(cloudWatchLogs).createLogGroupForAccount(ACCOUNT, "/aws/lambda/web/web", null, null, REGION);
        verify(cloudWatchLogs).createLogStreamForAccount(ACCOUNT, "/aws/lambda/web/web",
                STREAM + "-" + ACCOUNT + "-" + REGION, REGION);
        assertEquals("first\n", local.getValue().getFirst().get("message"));
    }

    @Test
    void aGroupDeletedAfterTheFirstEventIsCreatedAgain() {
        logs = new LambdaWebLogs(cloudWatchLogs);
        LambdaWebLogs.Sink sink = logs.open(ACCOUNT, REGION, "/custom", STREAM);
        sink.accept("first\n");
        verify(cloudWatchLogs, timeout(5_000)).putLogEventsForAccount(eq(ACCOUNT), eq("/custom"), any(), anyList(),
                eq(REGION));
        doThrow(new AwsException("ResourceNotFoundException", "The specified log group does not exist.", 400))
                .doReturn("token")
                .when(cloudWatchLogs).putLogEventsForAccount(eq(ACCOUNT), eq("/custom.local"), eq(STREAM), anyList(),
                        eq(REGION));

        sink.accept("second\n");

        verify(cloudWatchLogs, timeout(5_000).times(3)).putLogEventsForAccount(eq(ACCOUNT), eq("/custom.local"),
                eq(STREAM), anyList(), eq(REGION));
        verify(cloudWatchLogs, times(2)).createLogGroupForAccount(ACCOUNT, "/custom.local", null, null, REGION);
    }
}
