package com.durable.scheduler.domain;

public enum ReminderState {
    SCHEDULED,
    RUNNING,
    DELIVERED,
    CANCELLED,
    FAILED
}
