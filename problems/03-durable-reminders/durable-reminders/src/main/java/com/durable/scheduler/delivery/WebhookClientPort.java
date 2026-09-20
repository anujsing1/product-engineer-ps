package com.durable.scheduler.delivery;

/**
 * Outbound port for delivering a reminder notification (DIP).
 * Application code depends on this abstraction, not on RestClient/RestTemplate.
 */
public interface WebhookClientPort {

    /**
     * @return HTTP-style status code (e.g. 200 success, 503 temp failure, 400 terminal)
     */
    int deliver(String idempotencyKey, String content);
}
