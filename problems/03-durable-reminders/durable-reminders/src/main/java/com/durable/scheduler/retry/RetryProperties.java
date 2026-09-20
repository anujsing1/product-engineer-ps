package com.durable.scheduler.retry;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "scheduler.retry")
public class RetryProperties {

    /**
     * Maximum delivery attempts before terminal failure.
     */
    private int maxAttempts = 3;

    /**
     * Base delay for exponential backoff: delay = baseDelay * 2^(attempt-1).
     */
    private Duration baseDelay = Duration.ofSeconds(30);

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getBaseDelay() {
        return baseDelay;
    }

    public void setBaseDelay(Duration baseDelay) {
        this.baseDelay = baseDelay;
    }
}
