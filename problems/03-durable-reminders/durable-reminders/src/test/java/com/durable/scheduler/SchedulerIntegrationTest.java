package com.durable.scheduler;

import com.durable.scheduler.api.dto.ReminderRequestDto;
import com.durable.scheduler.delivery.DeliveryService;
import com.durable.scheduler.delivery.WebhookClientPort;
import com.durable.scheduler.domain.Reminder;
import com.durable.scheduler.domain.ReminderState;
import com.durable.scheduler.infrastructure.MockWebhookClientAdapter;
import com.durable.scheduler.repository.AttemptRepository;
import com.durable.scheduler.repository.ReminderRepository;
import com.durable.scheduler.service.ReminderService;
import com.durable.scheduler.support.MutableClock;
import com.durable.scheduler.worker.SchedulerWorker;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class SchedulerIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-06-15T10:00:00Z");

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

    @Autowired
    private DeliveryService deliveryService;

    @Autowired
    private EntityManager entityManager;

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
    void ac1_scheduledDelivery_withInjectedClock() {
        Reminder created = create("Take a break", "UTC", LocalDateTime.of(2026, 6, 15, 12, 0));
        assertThat(created.getExecutionInstant()).isEqualTo(Instant.parse("2026-06-15T12:00:00Z"));

        mutableClock.setInstant(Instant.parse("2026-06-15T12:00:01Z"));
        schedulerWorker.poll();

        Reminder delivered = reminderRepository.findById(created.getId()).orElseThrow();
        assertThat(delivered.getState()).isEqualTo(ReminderState.DELIVERED);
        assertThat(attemptRepository.countByReminderId(created.getId())).isEqualTo(1);
        assertThat(mockDestination.logicalDeliveryCount()).isEqualTo(1);
    }

    @Test
    void ac2_restartRecovery_overdueWorkDiscoveredOnPoll() {
        // Simulate: item became due while "service was stopped" — persisted SCHEDULED with past instant.
        Reminder overdue = create("Overdue after restart", "UTC", LocalDateTime.of(2026, 6, 15, 9, 0));
        assertThat(overdue.getExecutionInstant()).isBefore(mutableClock.instant());
        assertThat(overdue.getState()).isEqualTo(ReminderState.SCHEDULED);

        // "Restart" = process starts and worker polls; durable state is the source of truth.
        schedulerWorker.poll();

        assertThat(reminderRepository.findById(overdue.getId()).orElseThrow().getState())
                .isEqualTo(ReminderState.DELIVERED);
    }

    @Test
    void ac3_temporaryFailure_thenRetrySuccess() {
        Reminder created = create("FAIL_ONCE please", "UTC", LocalDateTime.of(2026, 6, 15, 9, 0));

        schedulerWorker.poll();
        Reminder afterFirst = reminderRepository.findById(created.getId()).orElseThrow();
        assertThat(afterFirst.getState()).isEqualTo(ReminderState.SCHEDULED);
        assertThat(attemptRepository.countByReminderId(created.getId())).isEqualTo(1);
        assertThat(afterFirst.getExecutionInstant()).isAfter(T0);

        mutableClock.setInstant(afterFirst.getExecutionInstant().plusSeconds(1));
        schedulerWorker.poll();

        Reminder afterRetry = reminderRepository.findById(created.getId()).orElseThrow();
        assertThat(afterRetry.getState()).isEqualTo(ReminderState.DELIVERED);
        assertThat(attemptRepository.countByReminderId(created.getId())).isEqualTo(2);
        assertThat(mockDestination.logicalDeliveryCount()).isEqualTo(1);
    }

    @Test
    void ac3_retryExhaustion_reachesFailed() {
        Reminder created = create("FAIL_ALWAYS forever", "UTC", LocalDateTime.of(2026, 6, 15, 9, 0));

        for (int i = 0; i < deliveryService.getMaxAttempts(); i++) {
            Reminder current = reminderRepository.findById(created.getId()).orElseThrow();
            if (current.getState() == ReminderState.SCHEDULED) {
                mutableClock.setInstant(current.getExecutionInstant().plusSeconds(1));
            }
            schedulerWorker.poll();
        }

        Reminder failed = reminderRepository.findById(created.getId()).orElseThrow();
        assertThat(failed.getState()).isEqualTo(ReminderState.FAILED);
        assertThat(attemptRepository.countByReminderId(created.getId()))
                .isEqualTo(deliveryService.getMaxAttempts());
        assertThat(mockDestination.logicalDeliveryCount()).isZero();
    }

    @Test
    void ac4_duplicateExecution_oneLogicalNotification() {
        int before = mockDestination.logicalDeliveryCount();
        String key = "rem-dup_1_1";

        assertThat(webhookClientPort.deliver(key, "hello")).isEqualTo(200);
        assertThat(webhookClientPort.deliver(key, "hello")).isEqualTo(200);

        assertThat(mockDestination.logicalDeliveryCount()).isEqualTo(before + 1);
        assertThat(mockDestination.processedCount()).isEqualTo(1);
    }

    @Test
    void ac5_editBeforeExecution_supersedesOldSchedule() {
        Reminder created = create("Original", "UTC", LocalDateTime.of(2026, 6, 15, 12, 0));
        Long versionBefore = created.getVersion();

        ReminderRequestDto edit = new ReminderRequestDto();
        edit.setContent("Updated copy");
        edit.setTargetZoneId("UTC");
        edit.setRequestedLocalTime(LocalDateTime.of(2026, 6, 15, 15, 0));
        Reminder edited = reminderService.editReminder(created.getId(), edit);

        assertThat(edited.getVersion()).isGreaterThan(versionBefore);
        assertThat(edited.getExecutionInstant()).isEqualTo(Instant.parse("2026-06-15T15:00:00Z"));
        assertThat(edited.getContent()).isEqualTo("Updated copy");

        // Old schedule time elapses — must NOT deliver yet.
        mutableClock.setInstant(Instant.parse("2026-06-15T12:00:01Z"));
        schedulerWorker.poll();
        assertThat(reminderRepository.findById(created.getId()).orElseThrow().getState())
                .isEqualTo(ReminderState.SCHEDULED);

        mutableClock.setInstant(Instant.parse("2026-06-15T15:00:01Z"));
        schedulerWorker.poll();
        Reminder delivered = reminderRepository.findById(created.getId()).orElseThrow();
        assertThat(delivered.getState()).isEqualTo(ReminderState.DELIVERED);
        assertThat(delivered.getContent()).isEqualTo("Updated copy");
    }

    @Test
    void ac6_cancelBeforeDelivery_noSuccessfulDelivery() {
        Reminder created = create("Cancel me", "UTC", LocalDateTime.of(2026, 6, 15, 9, 0));
        reminderService.cancelReminder(created.getId());

        schedulerWorker.poll();

        Reminder cancelled = reminderRepository.findById(created.getId()).orElseThrow();
        assertThat(cancelled.getState()).isEqualTo(ReminderState.CANCELLED);
        assertThat(attemptRepository.countByReminderId(created.getId())).isZero();
        assertThat(mockDestination.logicalDeliveryCount()).isZero();
    }

    @Test
    void ac6_cancelRace_optimisticLockAbortsStaleWrite() {
        Reminder created = create("Race me", "UTC", LocalDateTime.of(2026, 6, 15, 14, 0));
        Reminder detached = reminderRepository.findById(created.getId()).orElseThrow();
        entityManager.detach(detached);

        reminderService.cancelReminder(created.getId());
        detached.markProcessing();

        assertThatThrownBy(() -> reminderRepository.saveAndFlush(detached))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void ac7_timeZones_kolkataAndNewYork_deterministicInstants() {
        Reminder kolkata = create("Kolkata tea", "Asia/Kolkata", LocalDateTime.of(2026, 6, 15, 15, 30));
        // IST is UTC+05:30 → 15:30 IST = 10:00 UTC
        assertThat(kolkata.getExecutionInstant()).isEqualTo(Instant.parse("2026-06-15T10:00:00Z"));

        Reminder nyc = create("NYC lunch", "America/New_York", LocalDateTime.of(2026, 6, 15, 12, 0));
        // EDT UTC-4 → 12:00 = 16:00Z
        assertThat(nyc.getExecutionInstant()).isEqualTo(Instant.parse("2026-06-15T16:00:00Z"));
    }

    @Test
    void ac7_dstOverlap_usesLaterOffset() {
        // US fall-back 2025-11-02: 01:30 occurs twice. Later occurrence is EST (UTC-5) → 06:30Z.
        Reminder overlap = create(
                "DST overlap",
                "America/New_York",
                LocalDateTime.of(2025, 11, 2, 1, 30));
        assertThat(overlap.getExecutionInstant()).isEqualTo(Instant.parse("2025-11-02T06:30:00Z"));
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
