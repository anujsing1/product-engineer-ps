package com.durable.scheduler.service;

import com.durable.scheduler.api.dto.ReminderRequestDto;
import com.durable.scheduler.domain.Reminder;
import com.durable.scheduler.repository.ReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final Clock clock;
    private final ReminderRepository reminderRepository;

    public ReminderService(Clock clock, ReminderRepository reminderRepository) {
        this.clock = clock;
        this.reminderRepository = reminderRepository;
    }

    @Transactional
    public Reminder createReminder(ReminderRequestDto dto) {
        Instant executionInstant = toUtcInstant(dto.getRequestedLocalTime(), dto.getTargetZoneId());
        Reminder reminder = new Reminder(
                dto.getContent(),
                dto.getTargetZoneId(),
                dto.getRequestedLocalTime(),
                executionInstant
        );
        Reminder saved = reminderRepository.save(reminder);
        log.info(
                "Created reminder id={} targetZoneId={} requestedLocalTime={} executionInstantUtc={}",
                saved.getId(),
                saved.getTargetZoneId(),
                saved.getRequestedLocalTime(),
                saved.getExecutionInstant());
        return saved;
    }

    @Transactional
    public Reminder editReminder(String id, ReminderRequestDto dto) {
        Reminder reminder = findReminder(id);
        Instant executionInstant = toUtcInstant(dto.getRequestedLocalTime(), dto.getTargetZoneId());
        log.info(
                "Edit requested for reminder id={} newTargetZoneId={} newRequestedLocalTime={} newExecutionInstantUtc={}",
                id,
                dto.getTargetZoneId(),
                dto.getRequestedLocalTime(),
                executionInstant);
        try {
            reminder.edit(
                    dto.getContent(),
                    dto.getTargetZoneId(),
                    dto.getRequestedLocalTime(),
                    executionInstant
            );
        } catch (IllegalStateException ex) {
            log.warn("Edit rejected for reminder id={}: {}", id, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
        Reminder saved = reminderRepository.save(reminder);
        log.info("Edit persisted for reminder id={} version={}", saved.getId(), saved.getVersion());
        return saved;
    }

    @Transactional
    public Reminder cancelReminder(String id) {
        Reminder reminder = findReminder(id);
        log.info("Cancellation requested for reminder id={} currentState={}", id, reminder.getState());
        try {
            reminder.cancel();
        } catch (IllegalStateException ex) {
            log.warn("Cancellation rejected for reminder id={}: {}", id, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
        Reminder saved = reminderRepository.save(reminder);
        log.info("Cancellation persisted for reminder id={} state={}", saved.getId(), saved.getState());
        return saved;
    }

    @Transactional(readOnly = true)
    public Reminder getReminder(String id) {
        return findReminder(id);
    }

    private Reminder findReminder(String id) {
        return reminderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reminder not found: " + id));
    }

    /**
     * Combines local wall-clock time with the target zone, resolving DST overlaps
     * deterministically via {@link ZonedDateTime#withLaterOffsetAtOverlap()}.
     */
    Instant toUtcInstant(LocalDateTime requestedLocalTime, String targetZoneId) {
        ZoneId zoneId = ZoneId.of(targetZoneId);
        ZonedDateTime zonedDateTime = requestedLocalTime
                .atZone(zoneId)
                .withLaterOffsetAtOverlap();
        return zonedDateTime.toInstant();
    }
}
