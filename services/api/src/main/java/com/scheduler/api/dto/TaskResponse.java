package com.scheduler.api.dto;

import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Response body for task endpoints. Matches the shape defined in blueprint Section 9.
 *
 * <pre>{@code
 * {
 *   "id": "f47ac10b-...",
 *   "taskType": "EMAIL",
 *   "status": "SCHEDULED",
 *   "priority": "HIGH",
 *   "scheduledAt": "2026-08-30T10:00:00Z",
 *   "maxAttempts": 5,
 *   "attemptCount": 0,
 *   "createdAt": "2026-08-28T14:00:00Z"
 * }
 * }</pre>
 *
 * <p>The payload is included in responses because callers need to confirm their
 * submission was stored correctly. The JSONB round-trip is tested in integration tests.
 */
public class TaskResponse {

    private UUID id;
    private String taskType;
    private TaskStatus status;
    private Priority priority;
    private OffsetDateTime scheduledAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private int maxAttempts;
    private int attemptCount;
    private String idempotencyKey;
    private String cronExpression;
    private boolean recurrenceEnabled;
    private String lastError;
    private Object payload; // returned as parsed JSON object, not raw string

    // ----- Static factory -----

    /**
     * Converts a {@link Task} JPA entity to a response DTO.
     *
     * <p>The payload is returned as a parsed JSON object (via Jackson's
     * {@code ObjectMapper}) rather than a raw string so the client receives
     * a proper JSON field, not an escaped string-within-JSON.
     */
    public static TaskResponse from(Task task, Object parsedPayload) {
        TaskResponse r = new TaskResponse();
        r.id              = task.getId();
        r.taskType        = task.getTaskType();
        r.status          = task.getStatus();
        r.priority        = task.getPriority();
        r.scheduledAt     = task.getScheduledAt();
        r.startedAt       = task.getStartedAt();
        r.completedAt     = task.getCompletedAt();
        r.createdAt       = task.getCreatedAt();
        r.updatedAt       = task.getUpdatedAt();
        r.maxAttempts     = task.getMaxAttempts();
        r.attemptCount    = task.getAttemptCount();
        r.idempotencyKey  = task.getIdempotencyKey();
        r.cronExpression  = task.getCronExpression();
        r.recurrenceEnabled = task.isRecurrenceEnabled();
        r.lastError       = task.getLastError();
        r.payload         = parsedPayload;
        return r;
    }

    // ----- Getters -----

    public UUID getId() { return id; }
    public String getTaskType() { return taskType; }
    public TaskStatus getStatus() { return status; }
    public Priority getPriority() { return priority; }
    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public OffsetDateTime getStartedAt() { return startedAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getAttemptCount() { return attemptCount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCronExpression() { return cronExpression; }
    public boolean isRecurrenceEnabled() { return recurrenceEnabled; }
    public String getLastError() { return lastError; }
    public Object getPayload() { return payload; }
}
