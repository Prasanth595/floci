package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.lambdaweb.LambdaWebService.WebFunctionTarget;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The CloudWatch metrics of Web Functions: one datapoint per request when it is answered, and one
 * per environment when it starts, each minute and when it ends. Floci serves an endpoint in its
 * home region only, so both namespaces go there.
 */
@ApplicationScoped
public class LambdaWebMetrics {

    static final String NAMESPACE = "AWS/Lambda/Web";
    static final String USAGE_NAMESPACE = "AWS/Lambda/Web/Usage";
    static final String REQUEST_THROTTLES = "EndpointRequestThrottles";
    static final String ACTIVE_ENVIRONMENT_THROTTLES = "EndpointActiveEnvironmentThrottles";
    private static final Logger LOG = Logger.getLogger(LambdaWebMetrics.class);

    /** One datapoint of each environment metric, for the time since the previous one. */
    interface EnvironmentMetrics {
        void publish(long activeMillis, long cpuMillis, long maxMemoryMb, int concurrentRequests, long responseBytes);
    }

    private final CloudWatchMetricsService cloudWatch;
    /** A persistent store writes to disk on each datapoint, which must not happen on the event loop. */
    private final ExecutorService publisher = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "lambda-web-metrics");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public LambdaWebMetrics(CloudWatchMetricsService cloudWatch) {
        this.cloudWatch = cloudWatch;
    }

    /** A request that reached an environment, with the status the caller got. */
    void executed(WebFunctionTarget target, int status, int activeEnvironments) {
        long now = Instant.now().getEpochSecond();
        List<Dimension> request = requestDimensions(target);
        List<Dimension> usage = usageDimensions(target);
        publish(target, NAMESPACE, datum("ExecutedRequests", "Count", 1, request, now));
        publish(target, USAGE_NAMESPACE, datum("ExecutedRequests", "Count", 1, usage, now));
        publish(target, USAGE_NAMESPACE, datum("EndpointActiveEnvironments", "Count", activeEnvironments, usage, now));
        statusClass(target, status, request, now);
    }

    /** A request the platform answered without an environment. */
    void answered(WebFunctionTarget target, int status) {
        statusClass(target, status, requestDimensions(target), Instant.now().getEpochSecond());
    }

    /** A request refused with 429. {@code metric} names the limit it hit. */
    void throttled(WebFunctionTarget target, String metric) {
        long now = Instant.now().getEpochSecond();
        publish(target, USAGE_NAMESPACE, datum(metric, "Count", 1, usageDimensions(target), now));
        statusClass(target, 429, requestDimensions(target), now);
    }

    EnvironmentMetrics environment(String accountId, String region, String functionName) {
        List<Dimension> dimensions = List.of(new Dimension("FunctionName", functionName));
        return (activeMillis, cpuMillis, maxMemoryMb, concurrentRequests, responseBytes) -> {
            long now = Instant.now().getEpochSecond();
            publish(accountId, region, NAMESPACE,
                    datum("EnvironmentActiveTime", "Milliseconds", activeMillis, dimensions, now));
            publish(accountId, region, NAMESPACE,
                    datum("EnvironmentCPUTime", "Milliseconds", cpuMillis, dimensions, now));
            publish(accountId, region, NAMESPACE,
                    datum("EnvironmentMaxMemoryUsage", "Megabytes", maxMemoryMb, dimensions, now));
            publish(accountId, region, NAMESPACE,
                    datum("EnvironmentConcurrentRequests", "Count", concurrentRequests, dimensions, now));
            publish(accountId, region, NAMESPACE, datum("ResponseBytes", "Bytes", responseBytes, dimensions, now));
        };
    }

    @PreDestroy
    void shutdown() {
        publisher.shutdown();
    }

    /** The status classes have no datapoint of 0. */
    private void statusClass(WebFunctionTarget target, int status, List<Dimension> dimensions, long now) {
        if (400 <= status && status <= 499) {
            publish(target, NAMESPACE, datum("HTTPResponseStatusClass4XX", "Count", 1, dimensions, now));
        } else if (500 <= status && status <= 599) {
            publish(target, NAMESPACE, datum("HTTPResponseStatusClass5XX", "Count", 1, dimensions, now));
        }
    }

    private static List<Dimension> requestDimensions(WebFunctionTarget target) {
        return List.of(new Dimension("FunctionName", target.function().functionName),
                new Dimension("EndpointName", target.endpoint().endpointName));
    }

    private static List<Dimension> usageDimensions(WebFunctionTarget target) {
        return List.of(new Dimension("FunctionName", target.function().functionName),
                new Dimension("EndpointName", target.endpoint().endpointName),
                new Dimension("ExecutionRegion", target.region()));
    }

    private static MetricDatum datum(String name, String unit, double value, List<Dimension> dimensions, long timestamp) {
        MetricDatum datum = new MetricDatum();
        datum.setMetricName(name);
        datum.setUnit(unit);
        datum.setValue(value);
        datum.setDimensions(dimensions);
        datum.setTimestamp(timestamp);
        return datum;
    }

    private void publish(WebFunctionTarget target, String namespace, MetricDatum datum) {
        publish(target.accountId(), target.region(), namespace, datum);
    }

    private void publish(String accountId, String region, String namespace, MetricDatum datum) {
        try {
            publisher.execute(() -> {
                try {
                    cloudWatch.publishMetricForAccount(accountId, namespace, datum, region, UUID.randomUUID().toString());
                } catch (RuntimeException e) {
                    LOG.warnv(e, "Could not publish {0} {1} in {2}", namespace, datum.getMetricName(), region);
                }
            });
        } catch (RejectedExecutionException expected) {
            // Floci is shutting down. The environments it stops then have no last datapoint.
        }
    }
}
