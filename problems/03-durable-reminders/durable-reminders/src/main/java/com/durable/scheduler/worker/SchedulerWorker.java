package com.durable.scheduler.worker;

import com.durable.scheduler.delivery.DeliveryService;
import com.durable.scheduler.domain.AttemptOutcome;
import com.durable.scheduler.domain.DeliveryAttempt;
import com.durable.scheduler.domain.Reminder;
import com.durable.scheduler.domain.ReminderState;
import com.durable.scheduler.repository.AttemptRepository;
import com.durable.scheduler.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SchedulerWorker {

    private static final Logger log = LoggerFactory.getLogger(SchedulerWorker.class);

    private final Clock clock;
    private final ReminderRepository reminderRepository;
    private final AttemptRepository attemptRepository;
    private final DeliveryService deliveryService;
    private final TransactionTemplate transactionTemplate;

    public SchedulerWorker(
            Clock clock,
            ReminderRepository reminderRepository,
            AttemptRepository attemptRepository,
            DeliveryService deliveryService,
            PlatformTransactionManager transactionManager
    ) {
        this.clock = clock;
        this.reminderRepository = reminderRepository;
        this.attemptRepository = attemptRepository;
        this.deliveryService = deliveryService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        Instant now = clock.instant();
        List<Reminder> dueWork = transactionTemplate.execute(status ->
                reminderRepository.findDueWork(now, PageRequest.of(0, 10)));

        if (dueWork == null || dueWork.isEmpty()) {
            return;
        }

        log.info("Poller claimed batchSize={} at clock={}", dueWork.size(), now);

        for (Reminder reminder : dueWork) {
            try {
                process(reminder.getId());
            } catch (ObjectOptimisticLockingFailureException ex) {
                log.warn(
                        "Edit/cancel race detected for reminder id={}: "
                                + "stale worker version lost optimistic lock; aborting stale execution.",
                        reminder.getId(),
                        ex);
            } catch (IllegalStateException ex) {
                log.warn(
                        "Entity state changed during delivery for reminder id={}; aborting stale execution: {}",
                        reminder.getId(),
                        ex.getMessage());
            }
        }
    }

    private void process(String reminderId) {
        AtomicLong attemptHolder = new AtomicLong();

        Reminder running = transactionTemplate.execute(status -> {
            Reminder reminder = reminderRepository.findById(reminderId)
                    .orElseThrow(() -> new IllegalStateException("Reminder disappeared: " + reminderId));
            if (reminder.getState() != ReminderState.SCHEDULED) {
                return null;
            }
            long attemptCount = attemptRepository.countByReminderId(reminder.getId()) + 1;
            attemptHolder.set(attemptCount);
            reminder.markProcessing();
            return reminderRepository.save(reminder);
        });

        if (running == null) {
            return;
        }

        int attemptCount = (int) attemptHolder.get();
        String idempotencyKey = running.getId() + "_" + running.getVersion() + "_" + attemptCount;

        log.info(
                "Dispatching delivery reminderId={} idempotencyKey={} attemptNumber={}",
                running.getId(),
                idempotencyKey,
                attemptCount);

        DeliveryService.DeliveryResult result =
                deliveryService.deliver(idempotencyKey, running.getContent(), attemptCount);

        transactionTemplate.executeWithoutResult(status -> {
            Reminder reminder = reminderRepository.findById(reminderId)
                    .orElseThrow(() -> new IllegalStateException("Reminder disappeared: " + reminderId));

            if (reminder.getState() != ReminderState.RUNNING) {
                log.warn(
                        "Reminder {} no longer RUNNING after delivery call (state={}); skipping finalize.",
                        reminderId,
                        reminder.getState());
                return;
            }

            Instant attemptedAt = clock.instant();
            AttemptOutcome outcome = result.outcome();
            if (outcome == AttemptOutcome.SUCCESS) {
                reminder.markDelivered(AttemptOutcome.SUCCESS);
                attemptRepository.save(new DeliveryAttempt(
                        reminder, AttemptOutcome.SUCCESS, attemptedAt,
                        "httpStatus=" + result.httpStatusCode()));
            } else if (outcome == AttemptOutcome.TEMP_FAILURE) {
                reminder.markFailed(true, result.nextAttemptTime());
                attemptRepository.save(new DeliveryAttempt(
                        reminder, AttemptOutcome.TEMP_FAILURE, attemptedAt,
                        "httpStatus=" + result.httpStatusCode() + "; next=" + result.nextAttemptTime()));
            } else {
                reminder.markFailed(false, null);
                attemptRepository.save(new DeliveryAttempt(
                        reminder, AttemptOutcome.TERMINAL_FAILURE, attemptedAt,
                        "httpStatus=" + result.httpStatusCode() + "; terminal"));
            }
            reminderRepository.save(reminder);
        });
    }
}
