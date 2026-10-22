package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends the AWS/SQS metrics of every active queue to CloudWatch once a minute, shaped as SQS sends
 * them: one sample of each depth gauge, and for each counter one sample per call since the previous
 * minute plus the zero sample AWS adds to every minute. SentMessageSize has one sample per message
 * and no zero sample.
 */
@ApplicationScoped
public class SqsMetricsPublisher {

    static final String NAMESPACE = "AWS/SQS";
    private static final Logger LOG = Logger.getLogger(SqsMetricsPublisher.class);
    private static final long PUBLISH_INTERVAL_SECONDS = 60;
    private static final String COUNT = "Count";

    private final SqsService sqsService;
    private final CloudWatchMetricsService metricsService;
    private final boolean enabled;
    private final ScheduledExecutorService scheduler;

    @Inject
    public SqsMetricsPublisher(SqsService sqsService, CloudWatchMetricsService metricsService, EmulatorConfig config) {
        this.sqsService = sqsService;
        this.metricsService = metricsService;
        this.enabled = config.services().sqs().enabled() && config.services().cloudwatchmetrics().enabled();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sqs-metrics-publisher");
            t.setDaemon(true);
            return t;
        });
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            return;
        }
        scheduler.scheduleAtFixedRate(() -> publish(Instant.now()), PUBLISH_INTERVAL_SECONDS,
                PUBLISH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        scheduler.shutdownNow();
    }

    void publish(Instant now) {
        try {
            for (SqsService.QueueMetrics queue : sqsService.collectQueueMetrics(now)) {
                metricsService.putMetricDataForAccount(queue.accountId(), NAMESPACE,
                        datums(queue, now.getEpochSecond()), queue.region());
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Publishing AWS/SQS queue metrics failed; this minute's samples are dropped");
        }
    }

    private static List<MetricDatum> datums(SqsService.QueueMetrics queue, long timestamp) {
        List<Dimension> dimensions = List.of(new Dimension("QueueName", queue.queueName()));
        GuardedMessageQueue.MessageCounts counts = queue.gauges().counts();
        SqsQueueActivity.Snapshot activity = queue.activity();
        List<MetricDatum> datums = new ArrayList<>(List.of(
                MetricDatum.of("ApproximateNumberOfMessagesVisible", COUNT, counts.visible(), dimensions, timestamp),
                MetricDatum.of("ApproximateNumberOfMessagesNotVisible", COUNT, counts.inFlight(), dimensions,
                        timestamp),
                MetricDatum.of("ApproximateNumberOfMessagesDelayed", COUNT, counts.delayed(), dimensions, timestamp),
                MetricDatum.of("ApproximateAgeOfOldestMessage", "Seconds",
                        queue.gauges().ageOfOldestMessageSeconds(), dimensions, timestamp),
                counter("NumberOfMessagesSent", activity.sent(), dimensions, timestamp),
                counter("NumberOfMessagesReceived", activity.received(), dimensions, timestamp),
                counter("NumberOfMessagesDeleted", activity.deleted(), dimensions, timestamp),
                counter("NumberOfEmptyReceives", activity.emptyReceives(), dimensions, timestamp)));
        if (activity.sentSize().count() > 0) {
            datums.add(samples("SentMessageSize", "Bytes", activity.sentSize(), dimensions, timestamp));
        }
        if (queue.fifo()) {
            datums.add(MetricDatum.of("ApproximateNumberOfGroupsWithInflightMessages", COUNT,
                    queue.gauges().groupsWithInflightMessages(), dimensions, timestamp));
            datums.add(counter("NumberOfDeduplicatedSentMessages", activity.deduplicated(), dimensions, timestamp));
        }
        return datums;
    }

    private static MetricDatum counter(String metricName, SqsQueueActivity.Samples calls,
                                       List<Dimension> dimensions, long timestamp) {
        return samples(metricName, COUNT, calls.add(0), dimensions, timestamp);
    }

    private static MetricDatum samples(String metricName, String unit, SqsQueueActivity.Samples samples,
                                       List<Dimension> dimensions, long timestamp) {
        MetricDatum datum = MetricDatum.of(metricName, unit, samples.sum(), dimensions, timestamp);
        datum.setSampleCount(samples.count());
        datum.setSum(samples.sum());
        datum.setMinimum(samples.minimum());
        datum.setMaximum(samples.maximum());
        return datum;
    }
}
