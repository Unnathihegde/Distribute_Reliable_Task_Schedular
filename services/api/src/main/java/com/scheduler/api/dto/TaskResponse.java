package com.scheduler.api.dto;

import com.scheduler.shared.domain.Priority;
import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * API response body for a single task.
 *
 * <p>Deliberately omits internal fields that are not relevant to API clients:
 * {@code worker_id}, {@code lease_id}, {@code lease_expires_at}.</p>
 *
 * <p>The {@code payload} field is returned as a raw JSON string. Clients can
 * parse it according to the {@code taskType} they submitted.</p>
 */
public class TaskResponse {

    private UUID id;
    private String taskType;
    private String payload;
    private TaskStatus status;
    private Priority priority;
    private Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private int attemptCount;
    private int maxAttempts;
    private String idempotencyKey;
    private String lastError;
    private String cronExpression;
    private boolean recurrenceEnabled;
    private String createdBy;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Converts a {@link Task} entity to a {@link TaskResponse}.
     *
     * <p>Kept as a static factory rather than a constructor to make the
     * mapping explicit and easily testable in isolation.</p>
     */
    public static TaskResponse from(Task task) {
        TaskResponse r = new TaskResponse();
        r.id                = task.getId();
        r.taskType          = task.getTaskType();
        r.payload           = task.getPayload();
        r.status            = task.getStatus();
        r.priority          = task.getPriority();
        r.scheduledAt       = task.getScheduledAt();
        r.startedAt         = task.getStartedAt();
        r.completedAt       = task.getCompletedAt();
        r.createdAt         = task.getCreatedAt();
        r.updatedAt         = task.getUpdatedAt();
        r.attemptCount      = task.getAttemptCount();
        r.maxAttempts       = task.getMaxAttempts();
        r.idempotencyKey    = task.getIdempotencyKey();
        r.lastError         = task.getLastError();
        r.cronExpression    = task.getCronExpression();
        r.recurrenceEnabled = task.isRecurrenceEnabled();
        r.createdBy         = task.getCreatedBy();
        return r;
    }

    // -------------------------------------------------------------------------
    // Getters — no setters; response objects are read-only after construction
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }
    public String getTaskType() { return taskType; }
    public String getPayload() { return payload; }
    public TaskStatus getStatus() { return status; }
    public Priority getPriority() { return priority; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int getAttemptCount() { return attemptCount; }
    public int getMaxAttempts() { return maxAttempts; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getLastError() { return lastError; }
    public String getCronExpression() { return cronExpression; }
    public boolean isRecurrenceEnabled() { return recurrenceEnabled; }
    public String getCreatedBy() { return createdBy; }
}
