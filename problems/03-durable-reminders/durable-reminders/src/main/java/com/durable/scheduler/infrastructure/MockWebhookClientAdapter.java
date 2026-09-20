package com.durable.scheduler.infrastructure;

import com.durable.scheduler.delivery.WebhookClientPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local fake webhook adapter (infrastructure). Content hooks:
 * <ul>
 *   <li>{@code FAIL_ONCE} — first attempt returns 503; later attempts return 200</li>
 *   <li>{@code FAIL_ALWAYS} — every attempt returns 500</li>
 * </ul>
 */
@Component
public class MockWebhookClientAdapter implements WebhookClientPort {

    private static final Logger log = LoggerFactory.getLogger(MockWebhookClientAdapter.class);

    private final ConcurrentHashMap<String, Boolean> seenKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> failOnceByReminder = new ConcurrentHashMap<>();
    private final AtomicInteger logicalDeliveries = new AtomicInteger();

    @Override
    public int deliver(String idempotencyKey, String content) {
        if (seenKeys.containsKey(idempotencyKey)) {
            log.info(
                    "Duplicate notification suppressed for idempotencyKey={} (key already delivered)",
                    idempotencyKey);
            return 200;
        }

        if (content != null && content.contains("FAIL_ALWAYS")) {
            log.warn("Simulated failure trigger FAIL_ALWAYS for idempotencyKey={}", idempotencyKey);
            return 500;
        }

        String reminderId = reminderIdFrom(idempotencyKey);
        if (content != null && content.contains("FAIL_ONCE")
                && failOnceByReminder.putIfAbsent(reminderId, Boolean.TRUE) == null) {
            log.warn(
                    "Simulated failure trigger FAIL_ONCE (503) for idempotencyKey={} reminderId={}",
                    idempotencyKey,
                    reminderId);
            return 503;
        }

        seenKeys.put(idempotencyKey, Boolean.TRUE);
        logicalDeliveries.incrementAndGet();
        log.info("Successful delivery for idempotencyKey={}", idempotencyKey);
        return 200;
    }

    public int processedCount() {
        return seenKeys.size();
    }

    public int logicalDeliveryCount() {
        return logicalDeliveries.get();
    }

    public void reset() {
        seenKeys.clear();
        failOnceByReminder.clear();
        logicalDeliveries.set(0);
    }

    private static String reminderIdFrom(String idempotencyKey) {
        int last = idempotencyKey.lastIndexOf('_');
        int second = idempotencyKey.lastIndexOf('_', last - 1);
        if (second <= 0) {
            return idempotencyKey;
        }
        return idempotencyKey.substring(0, second);
    }
}
