package com.durable.scheduler.retry;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class ExponentialBackoffRetryStrategy implements RetryPolicy {

    private final Clock clock;
    private final RetryProperties properties;

    public ExponentialBackoffRetryStrategy(Clock clock, RetryProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public boolean isRetryable(int httpStatusCode) {
        // 5xx (and network-style synthetic 503 from the mock) are temporary; 4xx are terminal.
        return httpStatusCode >= 500 && httpStatusCode <= 599;
    }

    @Override
    public Instant calculateNextAttemptTime(int currentAttemptCount) {
        int safeAttempt = Math.max(1, currentAttemptCount);
        long multiplier = 1L << (safeAttempt - 1);
        return clock.instant().plus(properties.getBaseDelay().multipliedBy(multiplier));
    }
}
