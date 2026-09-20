package com.durable.scheduler.api;

import com.durable.scheduler.api.dto.ReminderRequestDto;
import com.durable.scheduler.domain.Reminder;
import com.durable.scheduler.service.ReminderService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Reminder create(@Valid @RequestBody ReminderRequestDto request) {
        return reminderService.createReminder(request);
    }

    @GetMapping("/{id}")
    public Reminder get(@PathVariable String id) {
        return reminderService.getReminder(id);
    }

    @PutMapping("/{id}")
    public Reminder edit(@PathVariable String id, @Valid @RequestBody ReminderRequestDto request) {
        return reminderService.editReminder(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Reminder> cancel(@PathVariable String id) {
        Reminder cancelled = reminderService.cancelReminder(id);
        return ResponseEntity.ok(cancelled);
    }
}
