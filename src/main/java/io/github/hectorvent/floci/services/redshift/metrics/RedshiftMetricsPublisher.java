package io.github.hectorvent.floci.services.redshift.metrics;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.redshift.RedshiftService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends the AWS/Redshift metrics of every available cluster to CloudWatch once a minute. The samples
 * come from the {@link RedshiftMetricCollector} beans, so a new metric needs no change here. A tick
 * runs just after each minute boundary and stamps its samples with the minute that ended. Once an hour
 * it drops the AWS/Redshift datapoints older than the 15 days AWS keeps one-minute data.
 */
@ApplicationScoped
public class RedshiftMetricsPublisher {

    static final String NAMESPACE = "AWS/Redshift";
    private static final Logger LOG = Logger.getLogger(RedshiftMetricsPublisher.class);
    private static final long MINUTE_MILLIS = 60_000;
    private static final long TICK_OFFSET_MILLIS = 1_000;
    private static final Duration RETENTION = Duration.ofDays(15);

    private final RedshiftService redshiftService;
    private final CloudWatchMetricsService metricsService;
    private final Iterable<RedshiftMetricCollector> collectors;
    private final boolean enabled;
    private final ScheduledExecutorService scheduler;

    @Inject
    public RedshiftMetricsPublisher(RedshiftService redshiftService, CloudWatchMetricsService metricsService,
                                    Instance<RedshiftMetricCollector> collectors, EmulatorConfig config) {
        this(redshiftService, metricsService, collectors, isEnabled(config));
    }

    RedshiftMetricsPublisher(RedshiftService redshiftService, CloudWatchMetricsService metricsService,
                             Iterable<RedshiftMetricCollector> collectors, boolean enabled) {
        this.redshiftService = redshiftService;
        this.metricsService = metricsService;
        this.collectors = collectors;
        this.enabled = enabled;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "redshift-metrics-publisher");
            t.setDaemon(true);
            return t;
        });
    }

    static boolean isEnabled(EmulatorConfig config) {
        return config.services().redshift().enabled() && config.services().redshift().cloudwatchMetricsEnabled()
                && config.services().cloudwatchmetrics().enabled();
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            return;
        }
        long untilNextMinute = MINUTE_MILLIS - System.currentTimeMillis() % MINUTE_MILLIS;
        scheduler.scheduleAtFixedRate(() -> publish(Instant.now()), untilNextMinute + TICK_OFFSET_MILLIS,
                MINUTE_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Before EmulatorLifecycle's default-priority storage shutdown, letting a running tick finish its writes. */
    void onStop(@Observes @Priority(1000) ShutdownEvent ignored) {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("The AWS/Redshift metrics publisher did not finish its tick within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv(e, "Interrupted while stopping the AWS/Redshift metrics publisher");
        }
    }

    /**
     * Nothing may escape: the scheduler stops for good after a tick throws. One cluster's failed
     * collection or write drops only that cluster's samples for the minute.
     */
    void publish(Instant now) {
        List<RedshiftMetricTarget> targets;
        try {
            targets = redshiftService.availableMetricTargets();
        } catch (RuntimeException e) {
            LOG.warnv(e, "Listing Redshift clusters for AWS/Redshift metrics failed; this minute's samples are dropped");
            return;
        }
        long minute = endedMinute(now);
        for (RedshiftMetricTarget target : targets) {
            try {
                List<MetricDatum> datums = new ArrayList<>();
                for (RedshiftMetricCollector collector : collectors) {
                    datums.addAll(collector.collect(target, minute));
                }
                if (!datums.isEmpty()) {
                    metricsService.putMetricDataForAccount(target.accountId(), NAMESPACE, datums, target.region());
                }
            } catch (RuntimeException e) {
                LOG.warnv(e, "Publishing AWS/Redshift metrics for cluster {0} of account {1} in {2} failed; its "
                        + "samples for this minute are dropped", target.cluster().getClusterIdentifier(),
                        target.accountId(), target.region());
            }
        }
        if (minute % 3600 == 0) {
            try {
                metricsService.deleteMetricDataBefore(NAMESPACE, Instant.ofEpochSecond(minute).minus(RETENTION));
            } catch (RuntimeException e) {
                LOG.warnv(e, "Dropping AWS/Redshift datapoints older than {0} failed; the next hour tries again",
                        RETENTION);
            }
        }
    }

    /** The minute a tick's samples belong to. Reading it half a minute back absorbs any drift of the
     *  fixed-rate schedule away from the minute boundary. */
    private static long endedMinute(Instant tick) {
        return tick.minusSeconds(30).truncatedTo(ChronoUnit.MINUTES).getEpochSecond();
    }
}
