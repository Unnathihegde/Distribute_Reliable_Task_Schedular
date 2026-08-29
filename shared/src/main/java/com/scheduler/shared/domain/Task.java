package com.scheduler.shared.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity representing a single task in the distributed task scheduler.
 *
 * <p>Maps to the {@code tasks} table defined in {@code V1__create_tasks_table.sql}.
 * Every column, constraint, and index from the blueprint Section 8 is reflected here.
 *
 * <h2>JSONB mapping</h2>
 * <p>The {@code payload} column is PostgreSQL {@code JSONB}. Hibernate 6 (shipped with
 * Spring Boot 3.3.x) supports native JSON type mapping via
 * {@code @JdbcTypeCode(SqlTypes.JSON)}. This instructs Hibernate to bind the field
 * using PostgreSQL's JSON JDBC type rather than treating it as plain {@code VARCHAR},
 * which would cause a type-mismatch error at runtime
 * ({@code ERROR: column "payload" is of type jsonb but expression is of type character varying}).
 *
 * <h2>Enum strategy</h2>
 * <p>{@code status} and {@code priority} are stored as {@code VARCHAR} in the database
 * (matching the migration). The JPA entity maps them with {@link EnumType#STRING} so
 * the stored value is the human-readable name ({@code "SCHEDULED"}, {@code "HIGH"}, etc.)
 * rather than a fragile ordinal integer.
 *
 * <h2>CAS transitions</h2>
 * <p>Direct field mutation of {@code status} on this entity is intentionally not
 * how state transitions are performed in production. All state changes go through
 * {@link com.scheduler.shared.statemachine.TaskStateMachine#transition}, which
 * issues an atomic conditional {@code UPDATE ... WHERE status = :expected} via
 * {@link com.scheduler.shared.repository.TaskRepository#transitionStatus}.
 *
 * @see com.scheduler.shared.statemachine.TaskStateMachine
 * @see com.scheduler.shared.repository.TaskRepository
 */
@Entity
@Table(name = "tasks")
public class Task {

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    /**
     * Primary key — PostgreSQL {@code gen_random_uuid()} supplies the default.
     * Hibernate uses {@link GenerationType#UUID} to generate a UUID before insert
     * if not already set, consistent with the DB default.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Core task definition
    // -------------------------------------------------------------------------

    /**
     * Logical task type used to resolve the correct {@code TaskHandler}.
     * Examples: EMAIL, HTTP, DEMO.
     */
    @NotBlank(message = "taskType must not be blank")
    @Size(max = 50, message = "taskType must be at most 50 characters")
    @Column(name = "task_type", nullable = false, length = 50)
    private String taskType;

    /**
     * Task-specific data stored as PostgreSQL JSONB.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} tells Hibernate 6 to use the
     * {@code org.hibernate.type.descriptor.jdbc.JsonJdbcType} descriptor, which
     * sets JDBC parameter type to {@code java.sql.Types.OTHER} with the actual
     * type name {@code "json"} — exactly what PostgreSQL expects for a JSONB column.
     * Without this annotation, Hibernate defaults to {@code VARCHAR}, causing:
     * <pre>
     * ERROR: column "payload" is of type jsonb but expression is of type character varying
     * HINT: You will need to rewrite or cast the expression.
     * </pre>
     */
    @NotBlank(message = "payload must not be blank")
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    // -------------------------------------------------------------------------
    // Status and priority (enum columns)
    // -------------------------------------------------------------------------

    /**
     * Current lifecycle state. Stored as VARCHAR matching the {@code chk_status}
     * CHECK constraint. Default is {@link TaskStatus#SCHEDULED}.
     *
     * <p><strong>Never set this field directly in production code.</strong>
     * All transitions must go through
     * {@link com.scheduler.shared.statemachine.TaskStateMachine#transition}.
     */
    @NotNull(message = "status must not be null")
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TaskStatus status = TaskStatus.SCHEDULED;

    /**
     * Execution priority. Stored as VARCHAR matching the {@code chk_priority}
     * CHECK constraint. Default is {@link Priority#MEDIUM}.
     */
    @NotNull(message = "priority must not be null")
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private Priority priority = Priority.MEDIUM;

    // -------------------------------------------------------------------------
    // Scheduling timestamps
    // -------------------------------------------------------------------------

    /** When the task should first be dispatched. Defaults to now at insert time. */
    @NotNull(message = "scheduledAt must not be null")
    @Column(name = "scheduled_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime scheduledAt;

    /** Set by the worker when it acquires the lease ({@code QUEUED → RUNNING}). */
    @Column(name = "started_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime startedAt;

    /** Set when the task reaches a terminal state (SUCCESS, DEAD_LETTER, CANCELLED). */
    @Column(name = "completed_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime completedAt;

    /** Immutable creation timestamp. Set once by {@link #prePersist()}. */
    @Column(name = "created_at", nullable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;

    /** Updated on every state transition or field change. */
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime updatedAt;

    // -------------------------------------------------------------------------
    // Retry tracking
    // -------------------------------------------------------------------------

    /** Number of execution attempts completed so far. */
    @Min(value = 0, message = "attemptCount must be >= 0")
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    /**
     * Maximum allowed execution attempts. Mirrors the {@code chk_max_attempts}
     * DB CHECK constraint: {@code max_attempts >= 1 AND max_attempts <= 20}.
     */
    @Min(value = 1, message = "maxAttempts must be >= 1")
    @Max(value = 20, message = "maxAttempts must be <= 20")
    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    /** When the next retry should be dispatched (set when entering RETRY_WAIT). */
    @Column(name = "next_retry_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime nextRetryAt;

    // -------------------------------------------------------------------------
    // Lease (worker crash recovery)
    // -------------------------------------------------------------------------

    /** Identifier of the worker currently holding the lease. */
    @Size(max = 100, message = "workerId must be at most 100 characters")
    @Column(name = "worker_id", length = 100)
    private String workerId;

    /**
     * Unique per-execution lease identifier. The worker sets this when acquiring
     * the lease so that lease renewal and release can be scoped to the exact execution.
     */
    @Column(name = "lease_id")
    private UUID leaseId;

    /** When the current lease expires; checked by the recovery sweep. */
    @Column(name = "lease_expires_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime leaseExpiresAt;

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    /**
     * Client-supplied idempotency key. The unique partial index
     * {@code idx_tasks_idempotency_key} enforces uniqueness at the DB level.
     */
    @Size(max = 255, message = "idempotencyKey must be at most 255 characters")
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    // -------------------------------------------------------------------------
    // Error tracking
    // -------------------------------------------------------------------------

    /** Last failure message from the task handler or infrastructure. */
    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    // -------------------------------------------------------------------------
    // Recurrence
    // -------------------------------------------------------------------------

    /**
     * Quartz-compatible cron expression for recurring tasks.
     * Null for one-shot tasks.
     */
    @Size(max = 100, message = "cronExpression must be at most 100 characters")
    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    /**
     * Whether this task auto-generates the next occurrence after SUCCESS.
     * Defaults to {@code false} for one-shot tasks.
     */
    @Column(name = "recurrence_enabled", nullable = false)
    private boolean recurrenceEnabled = false;

    /**
     * For recurring tasks, the parent task that originated this occurrence.
     * Null for the first occurrence and all one-shot tasks.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_task_id")
    private Task parentTask;

    // -------------------------------------------------------------------------
    // Audit
    // -------------------------------------------------------------------------

    /** Human-readable identifier of who/what created this task (e.g., service name). */
    @Size(max = 100, message = "createdBy must be at most 100 characters")
    @Column(name = "created_by", length = 100)
    private String createdBy;

    // -------------------------------------------------------------------------
    // JPA lifecycle callbacks
    // -------------------------------------------------------------------------

    /**
     * Sets immutable timestamps and applies defaults before the first INSERT.
     * This mirrors the {@code DEFAULT now()} clauses in the SQL schema.
     */
    @PrePersist
    void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (scheduledAt == null) {
            scheduledAt = now;
        }
        // Apply enum defaults if somehow null (defensive — fields have initializers)
        if (status == null) {
            status = TaskStatus.SCHEDULED;
        }
        if (priority == null) {
            priority = Priority.MEDIUM;
        }
    }

    /**
     * Updates {@code updated_at} on every subsequent UPDATE.
     */
    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // -------------------------------------------------------------------------
    // Getters and setters
    // (No Lombok by design — this is a shared library; explicit accessors avoid
    //  requiring Lombok on the classpath of every consuming module.)
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    public OffsetDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(OffsetDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public OffsetDateTime getCreatedAt() { return createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public OffsetDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(OffsetDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public UUID getLeaseId() { return leaseId; }
    public void setLeaseId(UUID leaseId) { this.leaseId = leaseId; }

    public OffsetDateTime getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(OffsetDateTime leaseExpiresAt) { this.leaseExpiresAt = leaseExpiresAt; }

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

    @Override
    public String toString() {
        return "Task{id=" + id + ", taskType='" + taskType + "', status=" + status +
               ", priority=" + priority + ", scheduledAt=" + scheduledAt + "}";
    }
}
