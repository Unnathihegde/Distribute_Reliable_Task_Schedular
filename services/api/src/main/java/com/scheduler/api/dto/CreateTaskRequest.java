package com.scheduler.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.scheduler.api.validation.ValidCronExpression;
import com.scheduler.api.validation.ValidTaskType;
import com.scheduler.shared.domain.Priority;
import jakarta.validation.constraints.*;

import java.time.Instant;

/**
 * Request body for {@code POST /api/v1/tasks}.
 *
 * <h3>Validation strategy</h3>
 * <ul>
 *   <li>{@code taskType} — whitelisted via custom {@link ValidTaskType}.</li>
 *   <li>{@code payload} — raw JSON string; {@code @NotBlank} ensures it is present.
 *       The service layer validates it is parseable JSON and within the 65 536-byte limit.</li>
 *   <li>{@code priority} — Jackson deserialises directly to the {@link Priority} enum;
 *       an unrecognised value causes {@code HttpMessageNotReadableException} (→ 400).</li>
 *   <li>{@code scheduledAt} — optional; the service validates it is not more than
 *       5 minutes in the past if present.</li>
 *   <li>{@code maxAttempts} — clamped to [1, 20] by the schema constraint.</li>
 *   <li>{@code cronExpression} — syntactically validated against Quartz format.</li>
 * </ul>
 */
public class CreateTaskRequest {

    @NotBlank(message = "taskType is required")
    @Size(max = 50, message = "taskType must not exceed 50 characters")
    @ValidTaskType
    private String taskType;

    /**
     * Task-specific data as a raw JSON string.
     * Maximum 65 536 bytes (validated in the service layer against the
     * serialised form to avoid parsing overhead here).
     */
    @NotBlank(message = "payload is required")
    private String payload;

    /**
     * Optional scheduling time. If null or in the past (within the 5-minute grace
     * window), the task is scheduled immediately. If in the future, the task waits
     * until the scheduler polls at or after this time.
     */
    private Instant scheduledAt;

    /**
     * Execution priority. Defaults to MEDIUM if not supplied.
     * Invalid values cause a 400 INVALID_REQUEST_BODY response.
     */
    private Priority priority = Priority.MEDIUM;

    @Min(value = 1, message = "maxAttempts must be at least 1")
    @Max(value = 20, message = "maxAttempts must be at most 20")
    private int maxAttempts = 5;

    /**
     * Client-supplied idempotency key. If a task with this key already exists,
     * the existing task is returned with HTTP 200 instead of creating a duplicate.
     */
    @JsonProperty("idempotencyKey")
    @Size(max = 255, message = "idempotencyKey must not exceed 255 characters")
    private String idempotencyKey;

    /**
     * Quartz cron expression for recurring tasks.
     * Example: {@code 0 0 12 * * ?} (every day at noon).
     */
    @ValidCronExpression
    private String cronExpression;

    private boolean recurrenceEnabled = false;

    @Size(max = 100, message = "createdBy must not exceed 100 characters")
    private String createdBy;

    // -------------------------------------------------------------------------
    // Getters & setters
    // -------------------------------------------------------------------------

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public boolean isRecurrenceEnabled() { return recurrenceEnabled; }
    public void setRecurrenceEnabled(boolean recurrenceEnabled) { this.recurrenceEnabled = recurrenceEnabled; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
