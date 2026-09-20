package com.durable.scheduler.delivery;

import com.durable.scheduler.domain.AttemptOutcome;
import com.durable.scheduler.retry.RetryPolicy;
import com.durable.scheduler.retry.RetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

/**
 * Application delivery orchestrator. Depends on {@link WebhookClientPort} and {@link RetryPolicy}
 * abstractions — never on RestClient/RestTemplate directly.
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

    private final WebhookClientPort webhookClientPort;
    private final RetryPolicy retryPolicy;
    private final RetryProperties retryProperties;

    public DeliveryService(
            WebhookClientPort webhookClientPort,
            RetryPolicy retryPolicy,
            RetryProperties retryProperties
    ) {
        this.webhookClientPort = webhookClientPort;
        this.retryPolicy = retryPolicy;
        this.retryProperties = retryProperties;
    }

    public DeliveryResult deliver(String idempotencyKey, String content, int currentAttemptCount) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        int httpStatus = webhookClientPort.deliver(idempotencyKey, content);
        log.info(
                "Webhook client returned status={} for idempotencyKey={} attempt={}",
                httpStatus, idempotencyKey, currentAttemptCount);

        if (httpStatus >= 200 && httpStatus < 300) {
            return DeliveryResult.success(httpStatus);
        }

        boolean retryableStatus = retryPolicy.isRetryable(httpStatus);
        boolean attemptsRemain = currentAttemptCount < retryProperties.getMaxAttempts();

        if (retryableStatus && attemptsRemain) {
            Instant next = retryPolicy.calculateNextAttemptTime(currentAttemptCount);
            return DeliveryResult.temporaryFailure(httpStatus, next);
        }

        return DeliveryResult.terminalFailure(httpStatus);
    }

    public int getMaxAttempts() {
        return retryProperties.getMaxAttempts();
    }

    public record DeliveryResult(AttemptOutcome outcome, int httpStatusCode, Instant nextAttemptTime) {

        public static DeliveryResult success(int httpStatusCode) {
            return new DeliveryResult(AttemptOutcome.SUCCESS, httpStatusCode, null);
        }

        public static DeliveryResult temporaryFailure(int httpStatusCode, Instant nextAttemptTime) {
            return new DeliveryResult(AttemptOutcome.TEMP_FAILURE, httpStatusCode, nextAttemptTime);
        }

        public static DeliveryResult terminalFailure(int httpStatusCode) {
            return new DeliveryResult(AttemptOutcome.TERMINAL_FAILURE, httpStatusCode, null);
        }
    }
}
