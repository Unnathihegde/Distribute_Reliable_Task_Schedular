package com.scheduler.shared.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

/**
 * The central aggregate root of the scheduling system.
 *
 * <p>Every task passes through a well-defined lifecycle governed by {@code status}.
 * The database is the single source of truth: all state transitions are atomic
 * conditional UPDATEs, ensuring consistency even with multiple scheduler/worker
 * instances running concurrently.</p>
 *
 * <p>Key design decisions:</p>
 * <ul>
 *   <li>{@code payload} is JSONB — schemaless task-specific data that varies per task_type.</li>
 *   <li>{@code idempotency_key} has a partial unique index (non-null only), allowing
 *       tasks without keys while preventing duplicate keyed submissions.</li>
 *   <li>{@code lease_id} / {@code lease_expires_at} enable worker crash recovery:
 *       if a worker dies, the lease expires and another worker can safely claim the task.</li>
 *   <li>Timestamps use {@code Instant} (UTC) — no timezone ambiguity.</li>
 * </ul>
 */
@Entity
@Table(name = "tasks")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Core task identity
    // -------------------------------------------------------------------------

    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    /**
     * Task-specific data stored as a JSONB string.
     * Mapped via {@link JsonbType} to correctly set the PostgreSQL JSONB OID
     * rather than VARCHAR, which PostgreSQL rejects.
     */
    @Type(JsonbType.class)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status = TaskStatus.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private Priority priority = Priority.MEDIUM;

    // -------------------------------------------------------------------------
    // Scheduling
    // -------------------------------------------------------------------------

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // -------------------------------------------------------------------------
    // Retry
    // -------------------------------------------------------------------------

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    // -------------------------------------------------------------------------
    // Lease (worker crash recovery)
    // -------------------------------------------------------------------------

    /** Identifies the worker instance currently executing this task. */
    @Column(name = "worker_id", length = 100)
    private String workerId;

    /**
     * A per-execution UUID. A worker must present the correct lease ID when
     * marking a task complete, preventing stale workers from corrupting state.
     */
    @Column(name = "lease_id")
    private UUID leaseId;

    /**
     * Wall-clock deadline by which the worker must complete or renew the lease.
     * Tasks with {@code lease_expires_at < now()} and status=RUNNING are
     * eligible for crash recovery by the scheduler.
     */
    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    /**
     * Client-supplied idempotency key. Protected by a partial unique index
     * {@code idx_tasks_idempotency_key} (non-null rows only).
     */
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    // -------------------------------------------------------------------------
    // Error tracking
    // -------------------------------------------------------------------------

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    // -------------------------------------------------------------------------
    // Recurrence
    // -------------------------------------------------------------------------

    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    @Column(name = "recurrence_enabled", nullable = false)
    private boolean recurrenceEnabled = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_task_id")
    private Task parentTask;

    // -------------------------------------------------------------------------
    // Audit & Tracing
    // -------------------------------------------------------------------------

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "traceparent", length = 255)
    private String traceparent;

    // -------------------------------------------------------------------------
    // Lifecycle callbacks
    // -------------------------------------------------------------------------

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
        if (scheduledAt == null) scheduledAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    protected Task() {
        // JPA requires a no-arg constructor
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public UUID getLeaseId() { return leaseId; }
    public void setLeaseId(UUID leaseId) { this.leaseId = leaseId; }

    public Instant getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Instant leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public boolean isRecurrenceEnabled() { return recurrenceEnabled; }
    public void setRecurrenceEnabled(boolean recurrenceEnabled) { this.recurrenceEnabled = recurrenceEnabled; }

    public Task getParentTask() { return parentTask; }
    public void setParentTask(Task parentTask) { this.parentTask = parentTask; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }

    public String getTraceparent() { return traceparent; }
    public void setTraceparent(String traceparent) { this.traceparent = traceparent; }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Task task = new Task();

        public Builder taskType(String taskType) { task.taskType = taskType; return this; }
        public Builder payload(String payload) { task.payload = payload; return this; }
        public Builder status(TaskStatus status) { task.status = status; return this; }
        public Builder priority(Priority priority) { task.priority = priority; return this; }
        public Builder scheduledAt(Instant scheduledAt) { task.scheduledAt = scheduledAt; return this; }
        public Builder maxAttempts(int maxAttempts) { task.maxAttempts = maxAttempts; return this; }
        public Builder idempotencyKey(String key) { task.idempotencyKey = key; return this; }
        public Builder cronExpression(String cron) { task.cronExpression = cron; return this; }
        public Builder recurrenceEnabled(boolean enabled) { task.recurrenceEnabled = enabled; return this; }
        public Builder createdBy(String createdBy) { task.createdBy = createdBy; return this; }
        public Builder parentTask(Task parentTask) { task.parentTask = parentTask; return this; }
        public Builder deletedAt(Instant deletedAt) { task.deletedAt = deletedAt; return this; }
        public Builder traceparent(String traceparent) { task.traceparent = traceparent; return this; }

        public Task build() {
            return task;
        }
    }
}
