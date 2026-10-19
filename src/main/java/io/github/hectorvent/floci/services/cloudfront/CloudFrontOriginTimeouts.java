package io.github.hectorvent.floci.services.cloudfront;

import java.time.Duration;

/**
 * The origin-timeout settings of a CloudFront distribution, shared by the distribution API (which
 * validates and emits them) and the serving layer (which applies them to origin requests), so both
 * sides agree on the values the {@code Origin} and {@code CustomOriginConfig} API references
 * document. {@code OriginReadTimeout} accepts 1-120 seconds, default 30; {@code ConnectionTimeout}
 * accepts 1-10 seconds, default 10; {@code ConnectionAttempts} accepts 1-3, default 3.
 */
final class CloudFrontOriginTimeouts {

    static final int DEFAULT_ORIGIN_READ_TIMEOUT_SECONDS = 30;
    static final int MIN_ORIGIN_READ_TIMEOUT_SECONDS = 1;
    static final int MAX_ORIGIN_READ_TIMEOUT_SECONDS = 120;
    static final int DEFAULT_CONNECTION_TIMEOUT_SECONDS = 10;
    static final int MIN_CONNECTION_TIMEOUT_SECONDS = 1;
    static final int MAX_CONNECTION_TIMEOUT_SECONDS = 10;
    static final int DEFAULT_CONNECTION_ATTEMPTS = 3;
    static final int MAX_CONNECTION_ATTEMPTS = 3;

    static final Duration DEFAULT_ORIGIN_READ_TIMEOUT =
            Duration.ofSeconds(DEFAULT_ORIGIN_READ_TIMEOUT_SECONDS);
    static final Duration DEFAULT_CONNECTION_TIMEOUT =
            Duration.ofSeconds(DEFAULT_CONNECTION_TIMEOUT_SECONDS);

    private CloudFrontOriginTimeouts() {
    }
}
