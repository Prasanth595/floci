package io.github.hectorvent.floci.services.lambdaweb;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * The {@code rateLimit} of each endpoint: a bucket of {@code rateLimit} requests that refills at
 * {@code rateLimit} per second, so a brief burst above the rate passes and a sustained one does not.
 */
final class LambdaWebRateLimiter {

    private record Bucket(int rate, double tokens, long at) {
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    LambdaWebRateLimiter() {
        this(System::nanoTime);
    }

    LambdaWebRateLimiter(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /** Whether one more request to {@code endpointKey} fits under {@code rate} requests per second. */
    boolean tryAcquire(String endpointKey, int rate) {
        AtomicBoolean admitted = new AtomicBoolean();
        long now = nanoClock.getAsLong();
        buckets.compute(endpointKey, (key, bucket) -> {
            double tokens;
            if (bucket == null || bucket.rate() != rate) {
                tokens = rate;
            } else {
                double refill = rate * (double) (now - bucket.at()) / TimeUnit.SECONDS.toNanos(1);
                tokens = Math.min(rate, bucket.tokens() + refill);
            }
            if (tokens >= 1) {
                admitted.set(true);
                tokens -= 1;
            }
            return new Bucket(rate, tokens, now);
        });
        return admitted.get();
    }
}
