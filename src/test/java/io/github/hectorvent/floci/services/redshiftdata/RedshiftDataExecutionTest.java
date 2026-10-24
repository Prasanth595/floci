package io.github.hectorvent.floci.services.redshiftdata;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedshiftDataExecutionTest {

    @Test
    void runningCancellationCancelsJdbcAndPreventsCommit() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            Statement statement = mock(Statement.class);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RedshiftDataExecution handle = executor.submit("111111111111", "us-east-1", "query", execution -> {
                try {
                    execution.track(statement);
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    assertThrows(SQLException.class, () -> execution.commit(() -> fail("must not commit")));
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(executor.cancel("111111111111", "query"));
            verify(statement).cancel();
            release.countDown();
            handle.await(Duration.ofSeconds(5));
            assertTrue(handle.isDone());
            assertNull(workerFailure.get());
            assertFalse(executor.cancel("111111111111", "query"));
        }
    }

    @Test
    void terminalReleaseWaitsForAnInFlightCancellationSignal() throws Exception {
        RedshiftDataExecution execution = new RedshiftDataExecution(0);
        Statement statement = mock(Statement.class);
        CountDownLatch cancelling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        execution.track(statement);
        assertTrue(execution.requestCancellation());
        doAnswer(invocation -> {
            cancelling.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        }).when(statement).cancel();
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<?> signal = callers.submit(execution::signalCancellation);
            try {
                assertTrue(cancelling.await(5, TimeUnit.SECONDS));
                Future<?> terminal = callers.submit(execution::terminal);
                assertThrows(TimeoutException.class, () -> terminal.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                signal.get(5, TimeUnit.SECONDS);
                terminal.get(5, TimeUnit.SECONDS);
                execution.signalCancellation();
                verify(statement).cancel();
            } finally {
                release.countDown();
                execution.completed();
            }
        }
    }

    @Test
    void successfulCommitWinsOverCancellation() throws Exception {
        try (RedshiftDataExecutor executor = new RedshiftDataExecutor(1, 1, 1)) {
            AtomicReference<Throwable> workerFailure = new AtomicReference<>();
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            RedshiftDataExecution handle = executor.submit("111111111111", "us-east-1", "query", execution -> {
                try {
                    execution.commit(committed::countDown);
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (Throwable failure) {
                    workerFailure.set(failure);
                }
            });
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            assertFalse(executor.cancel("111111111111", "query"));
            release.countDown();
            handle.await(Duration.ofSeconds(5));
            assertTrue(handle.isDone());
            assertNull(workerFailure.get());
        }
    }
}
