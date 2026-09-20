package com.durable.scheduler;

import com.durable.scheduler.api.dto.ReminderRequestDto;
import com.durable.scheduler.delivery.WebhookClientPort;
import com.durable.scheduler.domain.Reminder;
import com.durable.scheduler.domain.ReminderState;
import com.durable.scheduler.infrastructure.MockWebhookClientAdapter;
import com.durable.scheduler.repository.AttemptRepository;
import com.durable.scheduler.repository.ReminderRepository;
import com.durable.scheduler.service.ReminderService;
import com.durable.scheduler.support.MutableClock;
import com.durable.scheduler.worker.SchedulerWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification benchmark (deterministic workflow-correctness).
 *
 * <pre>
 * ./mvnw test -Dtest=VerificationBenchmarkTest
 * </pre>
 */
@SpringBootTest
class VerificationBenchmarkTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MutableClock mutableClock;

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private AttemptRepository attemptRepository;

    @Autowired
    private SchedulerWorker schedulerWorker;

    @Autowired
    private WebhookClientPort webhookClientPort;

    private MockWebhookClientAdapter mockDestination;

    @BeforeEach
    void setUp() {
        mockDestination = (MockWebhookClientAdapter) webhookClientPort;
        attemptRepository.deleteAll();
        reminderRepository.deleteAll();
        mockDestination.reset();
        mutableClock.setInstant(T0);
    }

    @Test
    void verificationBenchmark_settlesWithExactLogicalDeliveries() {
        List<String> ids = new ArrayList<>();

        // 10 happy-path items across two zones (will become DELIVERED)
        for (int i = 0; i < 5; i++) {
            ids.add(create("ok-kolkata-" + i, "Asia/Kolkata", LocalDateTime.of(2026, 1, 1, 6, 0)).getId());
            ids.add(create("ok-nyc-" + i, "America/New_York", LocalDateTime.of(2026, 1, 1, 1, 0)).getId());
        }

        // 3 edited items (final schedule later in the day)
        for (int i = 0; i < 3; i++) {
            Reminder r = create("to-edit-" + i, "UTC", LocalDateTime.of(2026, 1, 1, 1, 0));
            ReminderRequestDto edit = new ReminderRequestDto();
            edit.setContent("edited-" + i);
            edit.setTargetZoneId("UTC");
            edit.setRequestedLocalTime(LocalDateTime.of(2026, 1, 1, 8, 0));
            reminderService.editReminder(r.getId(), edit);
            ids.add(r.getId());
        }

        // 3 cancelled
        for (int i = 0; i < 3; i++) {
            Reminder r = create("to-cancel-" + i, "UTC", LocalDateTime.of(2026, 1, 1, 1, 0));
            reminderService.cancelReminder(r.getId());
            ids.add(r.getId());
        }

        // 2 temporary-failure then success
        for (int i = 0; i < 2; i++) {
            ids.add(create("FAIL_ONCE temp-" + i, "UTC", LocalDateTime.of(2026, 1, 1, 1, 0)).getId());
        }

        // 2 permanent failures (retry exhaustion → FAILED)
        for (int i = 0; i < 2; i++) {
            ids.add(create("FAIL_ALWAYS perm-" + i, "UTC", LocalDateTime.of(2026, 1, 1, 1, 0)).getId());
        }

        assertThat(ids).hasSize(20);

        // Simulate service stopped while work became due: durable SCHEDULED rows remain.
        // Partial processing before "restart".
        mutableClock.setInstant(Instant.parse("2026-01-01T01:30:00Z"));
        schedulerWorker.poll();

        // Advance clock / poll until settled (covers restart continuation + retries + edits).
        for (int step = 0; step < 30; step++) {
            long pending = reminderRepository.countByState(ReminderState.SCHEDULED)
                    + reminderRepository.countByState(ReminderState.RUNNING);
            if (pending == 0) {
                break;
            }
            mutableClock.advanceBy(Duration.ofMinutes(30));
            schedulerWorker.poll();
        }

        Map<ReminderState, Long> counts = new EnumMap<>(ReminderState.class);
        for (ReminderState state : ReminderState.values()) {
            counts.put(state, reminderRepository.countByState(state));
        }

        int logicalBeforeDup = mockDestination.logicalDeliveryCount();

        // Duplicate execution for one occurrence key — destination must not double-count.
        String duplicateKey = "benchmark-occurrence_1_1";
        assertThat(webhookClientPort.deliver(duplicateKey, "dup-check")).isEqualTo(200);
        assertThat(webhookClientPort.deliver(duplicateKey, "dup-check")).isEqualTo(200);
        assertThat(mockDestination.logicalDeliveryCount()).isEqualTo(logicalBeforeDup + 1);

        System.out.println("=== Verification benchmark results ===");
        counts.forEach((state, count) -> System.out.println(state + "=" + count));
        System.out.println("successfulOccurrenceLogicalDeliveries=" + logicalBeforeDup);
        System.out.println("logicalDeliveriesAfterDupSimulation=" + mockDestination.logicalDeliveryCount());

        assertThat(counts.get(ReminderState.DELIVERED)).isEqualTo(15L); // 10 ok + 3 edited + 2 FAIL_ONCE
        assertThat(counts.get(ReminderState.CANCELLED)).isEqualTo(3L);
        assertThat(counts.get(ReminderState.FAILED)).isEqualTo(2L);
        assertThat(counts.get(ReminderState.SCHEDULED)).isZero();
        assertThat(counts.get(ReminderState.RUNNING)).isZero();
        assertThat(logicalBeforeDup).isEqualTo(15);
    }

    private Reminder create(String content, String zone, LocalDateTime localTime) {
        ReminderRequestDto request = new ReminderRequestDto();
        request.setContent(content);
        request.setTargetZoneId(zone);
        request.setRequestedLocalTime(localTime);
        return reminderService.createReminder(request);
    }

    @TestConfiguration
    static class ClockTestConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(T0, ZoneOffset.UTC);
        }
    }
}
