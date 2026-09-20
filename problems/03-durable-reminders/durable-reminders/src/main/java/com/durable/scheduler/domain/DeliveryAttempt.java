package com.durable.scheduler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {

    @Id
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reminder_id", nullable = false)
    private Reminder reminder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptOutcome outcome;

    @Column(nullable = false)
    private Instant attemptedAt;

    @Column
    private String detail;

    protected DeliveryAttempt() {
        // JPA
    }

    public DeliveryAttempt(Reminder reminder, AttemptOutcome outcome, Instant attemptedAt, String detail) {
        this.id = UUID.randomUUID().toString();
        this.reminder = Objects.requireNonNull(reminder, "reminder");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.attemptedAt = Objects.requireNonNull(attemptedAt, "attemptedAt");
        this.detail = detail;
    }

    public String getId() {
        return id;
    }

    public Reminder getReminder() {
        return reminder;
    }

    public AttemptOutcome getOutcome() {
        return outcome;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public String getDetail() {
        return detail;
    }
}
