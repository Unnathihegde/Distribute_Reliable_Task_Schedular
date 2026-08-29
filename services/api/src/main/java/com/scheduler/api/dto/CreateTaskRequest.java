package com.scheduler.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.scheduler.api.validation.ValidCronExpression;
import com.scheduler.api.validation.ValidTaskType;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;

/**
 * Request body for {@code POST /api/v1/tasks}.
 *
 * <p>All fields except {@code taskType} and {@code payload} are optional.
 * Validation rules match blueprint Section 9.
 *
 * <h2>Payload JSON validation</h2>
 * <p>The {@code payload} field is a raw JSON string from the client's perspective,
 * but it is stored as PostgreSQL JSONB. Validation happens in two stages:
 * <ol>
 *   <li>Bean Validation: {@code @NotBlank} ensures the field is present.</li>
 *   <li>Service layer: {@link com.scheduler.api.service.TaskService} validates
 *       that the payload is valid JSON and does not exceed 64 KB.</li>
 * </ol>
 * We accept payload as a raw {@code Object} (Jackson deserializes it as a Map/List)
 * and re-serialize to a JSON string before storing, which guarantees the stored
 * value is always valid, well-formed JSON regardless of what the client sends.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateTaskRequest {

    /**
     * Logical task type. Must be one of the registered types: EMAIL, HTTP, DEMO.
     * Blueprint Section 9 validation rule.
     */
    @NotBlank(message = "taskType is required")
    @ValidTaskType
    private String taskType;

    /**
     * Task-specific payload. Accepted as a generic JSON object from the client
     * and stored as JSONB in PostgreSQL. Size limit: 64 KB.
     *
     * <p>Accepting {@code Object} (not String) lets Jackson handle JSON parsing
     * before our validation runs — an invalid JSON body (e.g., unquoted string)
     * is rejected with 400 by Jackson before it even reaches our validator.
     */
    @NotNull(message = "payload is required")
    private Object payload;

    /**
     * Optional scheduled time. If absent, defaults to now (immediate dispatch).
     * If present, must not be more than 5 minutes in the past (Section 9).
     */
    private OffsetDateTime scheduledAt;

    /**
     * Execution priority. Defaults to MEDIUM if absent.
     * Must be one of HIGH, MEDIUM, LOW — deserialized as enum; Jackson rejects unknown values.
     */
    private String priority;

    /**
     * Maximum execution attempts. Range: 1–20. Defaults to 5.
     * Mirrors {@code chk_max_attempts} DB constraint.
     */
    @Min(value = 1, message = "maxAttempts must be at least 1")
    @Max(value = 20, message = "maxAttempts must be at most 20")
    private Integer maxAttempts;

    /**
     * Optional idempotency key. If provided, duplicate submissions with the same key
     * return the existing task (200 OK) rather than creating a new one.
     */
    @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
    private String idempotencyKey;

    /**
     * Optional Quartz cron expression. If present, task becomes recurring.
     * Must be a valid Quartz cron (6–7 fields).
     */
    @ValidCronExpression
    private String cronExpression;

    /**
     * Optional callback URL for webhook dispatch via the HTTP task handler.
     * Stored in the payload rather than as a top-level column (handler-specific config).
     */
    @Size(max = 2048, message = "callbackUrl must be at most 2048 characters")
    private String callbackUrl;

    // ----- Getters and setters -----

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }

    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(OffsetDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }

    public Integer getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
}
