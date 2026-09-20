package com.durable.scheduler.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public class ReminderRequestDto {

    @NotBlank
    private String content;

    @NotBlank
    private String targetZoneId;

    @NotNull
    private LocalDateTime requestedLocalTime;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getTargetZoneId() {
        return targetZoneId;
    }

    public void setTargetZoneId(String targetZoneId) {
        this.targetZoneId = targetZoneId;
    }

    public LocalDateTime getRequestedLocalTime() {
        return requestedLocalTime;
    }

    public void setRequestedLocalTime(LocalDateTime requestedLocalTime) {
        this.requestedLocalTime = requestedLocalTime;
    }
}
