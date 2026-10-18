package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Delivers the log events of Web Function environments. Each event goes to the stream of its
 * environment in the {@code .local} companion of the revision's log group, and to the same stream
 * with an {@code -<account>-<region>} suffix in the log group itself, where AWS centralizes the
 * regions an endpoint serves. A group or stream is created when its first event arrives.
 */
@ApplicationScoped
public class LambdaWebLogs {

    private static final Logger LOG = Logger.getLogger(LambdaWebLogs.class);
    private static final int MAX_QUEUED_EVENTS = 10_000;
    private static final int MAX_BATCH_EVENTS = 1_000;

    private final CloudWatchLogsService logs;
    private final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();

    @Inject
    public LambdaWebLogs(CloudWatchLogsService logs) {
        this.logs = logs;
    }

    public Sink open(String accountId, String region, String logGroup, String logStream) {
        return new Sink(accountId, region, List.of(
                new Destination(logGroup + ".local", logStream),
                new Destination(logGroup, logStream + "-" + accountId + "-" + region)));
    }

    @PreDestroy
    void shutdown() {
        writers.shutdownNow();
    }

    private record Destination(String logGroup, String logStream) {
    }

    private record Event(long timestamp, String message) {
    }

    /**
     * The events of one environment. {@link #accept} only queues, because the app blocks on its
     * output while the Docker stream that carries it waits. Past {@code MAX_QUEUED_EVENTS} events are dropped.
     */
    public final class Sink {
        private final String accountId;
        private final String region;
        private final List<Destination> destinations;
        private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger queued = new AtomicInteger();
        private final AtomicBoolean writing = new AtomicBoolean();

        private Sink(String accountId, String region, List<Destination> destinations) {
            this.accountId = accountId;
            this.region = region;
            this.destinations = destinations;
        }

        public void accept(String message) {
            if (queued.incrementAndGet() > MAX_QUEUED_EVENTS) {
                queued.decrementAndGet();
                LOG.debugv("Dropped a log event of {0}: too many are waiting", destinations.getFirst().logStream());
                return;
            }
            queue.add(new Event(System.currentTimeMillis(), message));
            schedule();
        }

        private void schedule() {
            if (!writing.compareAndSet(false, true)) {
                return;
            }
            try {
                writers.execute(this::write);
            } catch (RuntimeException e) {
                writing.set(false);
                LOG.debugv(e, "Could not deliver the log events of {0}", destinations.getFirst().logStream());
            }
        }

        private void write() {
            try {
                List<Map<String, Object>> batch = new ArrayList<>();
                Event event;
                while ((event = queue.poll()) != null) {
                    queued.decrementAndGet();
                    batch.add(Map.of("timestamp", event.timestamp(), "message", event.message()));
                    if (batch.size() == MAX_BATCH_EVENTS) {
                        put(batch);
                        batch = new ArrayList<>();
                    }
                }
                if (!batch.isEmpty()) {
                    put(batch);
                }
            } finally {
                writing.set(false);
                if (!queue.isEmpty()) {
                    schedule();
                }
            }
        }

        private void put(List<Map<String, Object>> batch) {
            for (Destination destination : destinations) {
                try {
                    putTo(destination, batch);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not deliver {0} log events to {1}", batch.size(), destination.logGroup());
                }
            }
        }

        /** The group and the stream are created when an event finds one of them missing, also after a delete. */
        private void putTo(Destination destination, List<Map<String, Object>> batch) {
            try {
                logs.putLogEventsForAccount(accountId, destination.logGroup(), destination.logStream(), batch, region);
            } catch (AwsException e) {
                if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                    throw e;
                }
                createLogGroup(destination);
                createLogStream(destination);
                logs.putLogEventsForAccount(accountId, destination.logGroup(), destination.logStream(), batch, region);
            }
        }

        private void createLogGroup(Destination destination) {
            try {
                logs.createLogGroupForAccount(accountId, destination.logGroup(), null, null, region);
            } catch (AwsException e) {
                ignoreAlreadyExists(e);
            }
        }

        private void createLogStream(Destination destination) {
            try {
                logs.createLogStreamForAccount(accountId, destination.logGroup(), destination.logStream(), region);
            } catch (AwsException e) {
                ignoreAlreadyExists(e);
            }
        }

        private static void ignoreAlreadyExists(AwsException e) {
            if (!"ResourceAlreadyExistsException".equals(e.getErrorCode())) {
                throw e;
            }
        }
    }
}
