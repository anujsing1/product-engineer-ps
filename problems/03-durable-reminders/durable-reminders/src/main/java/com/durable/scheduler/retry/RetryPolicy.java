package com.durable.scheduler.retry;

import java.time.Instant;

/**
 * Strategy for classifying HTTP outcomes and computing the next attempt instant.
 */
public interface RetryPolicy {

    boolean isRetryable(int httpStatusCode);

    Instant calculateNextAttemptTime(int currentAttemptCount);
}
