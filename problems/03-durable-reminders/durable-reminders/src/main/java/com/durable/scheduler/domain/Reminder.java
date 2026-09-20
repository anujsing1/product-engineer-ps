package com.durable.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Rich aggregate: {@code state} has no setter. Transitions only via domain methods.
 */
@Entity
@Table(name = "reminder")
public class Reminder {

    private static final Logger log = LoggerFactory.getLogger(Reminder.class);

    @Id
    private String id;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private String targetZoneId;

    @Column(nullable = false)
    private LocalDateTime requestedLocalTime;

    @Column(nullable = false)
    private Instant executionInstant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReminderState state = ReminderState.SCHEDULED;

    @Version
    private Long version;

    protected Reminder() {
        // JPA
    }

    public Reminder(String content, String targetZoneId, LocalDateTime requestedLocalTime, Instant executionInstant) {
        this.id = UUID.randomUUID().toString();
        this.content = Objects.requireNonNull(content, "content");
        this.targetZoneId = Objects.requireNonNull(targetZoneId, "targetZoneId");
        this.requestedLocalTime = Objects.requireNonNull(requestedLocalTime, "requestedLocalTime");
        this.executionInstant = Objects.requireNonNull(executionInstant, "executionInstant");
        this.state = ReminderState.SCHEDULED;
    }

    /** SCHEDULED → RUNNING (processing / in-flight delivery). */
    public void markProcessing() {
        if (state != ReminderState.SCHEDULED) {
            throw new IllegalStateException(
                    "Cannot mark reminder as PROCESSING/RUNNING from state " + state + " (id=" + id + ")");
        }
        ReminderState from = this.state;
        this.state = ReminderState.RUNNING;
        log.info("State transition id={} {} -> {}", id, from, state);
    }

    /** RUNNING → DELIVERED. Outcome must be {@link AttemptOutcome#SUCCESS}. */
    public void markDelivered(AttemptOutcome outcome) {
        if (state != ReminderState.RUNNING) {
            throw new IllegalStateException(
                    "Cannot mark reminder as DELIVERED from state " + state + " (id=" + id + ")");
        }
        if (outcome != AttemptOutcome.SUCCESS) {
            throw new IllegalStateException(
                    "markDelivered requires SUCCESS outcome, got " + outcome + " (id=" + id + ")");
        }
        ReminderState from = this.state;
        this.state = ReminderState.DELIVERED;
        log.info("State transition id={} {} -> {} outcome={}", id, from, state, outcome);
    }

    /**
     * RUNNING → SCHEDULED (retryable) or FAILED (terminal).
     * Invalid from terminal states such as DELIVERED.
     */
    public void markFailed(boolean isRetryable, Instant nextAttemptTime) {
        if (state != ReminderState.RUNNING) {
            throw new IllegalStateException(
                    "Cannot mark reminder as failed from state " + state + " (id=" + id + ")");
        }
        ReminderState from = this.state;
        if (isRetryable) {
            Objects.requireNonNull(nextAttemptTime, "nextAttemptTime is required when retryable");
            this.executionInstant = nextAttemptTime;
            this.state = ReminderState.SCHEDULED;
            log.info(
                    "State transition id={} {} -> {} isRetryable=true nextAttemptTime={}",
                    id, from, state, nextAttemptTime);
        } else {
            this.state = ReminderState.FAILED;
            log.info("State transition id={} {} -> {} isRetryable=false (terminal)", id, from, state);
        }
    }

    public void edit(String content, String targetZoneId, LocalDateTime requestedLocalTime, Instant executionInstant) {
        if (state != ReminderState.SCHEDULED) {
            throw new IllegalStateException(
                    "Cannot edit reminder in state " + state + " (id=" + id + ")");
        }
        this.content = Objects.requireNonNull(content, "content");
        this.targetZoneId = Objects.requireNonNull(targetZoneId, "targetZoneId");
        this.requestedLocalTime = Objects.requireNonNull(requestedLocalTime, "requestedLocalTime");
        this.executionInstant = Objects.requireNonNull(executionInstant, "executionInstant");
    }

    public void cancel() {
        if (state == ReminderState.DELIVERED
                || state == ReminderState.CANCELLED
                || state == ReminderState.FAILED) {
            throw new IllegalStateException(
                    "Cannot cancel reminder in state " + state + " (id=" + id + ")");
        }
        ReminderState from = this.state;
        this.state = ReminderState.CANCELLED;
        log.info("State transition id={} {} -> {}", id, from, state);
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public String getTargetZoneId() {
        return targetZoneId;
    }

    public LocalDateTime getRequestedLocalTime() {
        return requestedLocalTime;
    }

    public Instant getExecutionInstant() {
        return executionInstant;
    }

    public ReminderState getState() {
        return state;
    }

    public Long getVersion() {
        return version;
    }
}
