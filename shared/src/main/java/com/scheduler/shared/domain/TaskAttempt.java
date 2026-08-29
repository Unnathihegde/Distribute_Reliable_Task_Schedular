package com.scheduler.shared.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity representing a single execution attempt for a {@link Task}.
 *
 * <p>Maps to the {@code task_attempts} table defined in
 * {@code V2__create_task_attempts_table.sql}.
 *
 * <p>While {@link Task} tracks the <em>current</em> state of a task, this table
 * provides the full audit trail of every execution attempt — essential for debugging
 * and answering "what happened to this task across all its retries?".
 *
 * <p>Records are written by the Worker service after each execution attempt. They are
 * read-only from the perspective of the API and Scheduler services.
 */
@Entity
@Table(
    name = "task_attempts",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_task_attempt",
            columnNames = {"task_id", "attempt_number"}
        )
    }
)
public class TaskAttempt {

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // -------------------------------------------------------------------------
    // Relationship to parent task
    // -------------------------------------------------------------------------

    /**
     * The task this attempt belongs to.
     * FK references {@code tasks(id)}; LAZY to avoid N+1 queries.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    private Task task;

    // -------------------------------------------------------------------------
    // Attempt metadata
    // -------------------------------------------------------------------------

    /**
     * 1-based attempt number for this task. Together with {@code task_id} forms
     * a unique key enforced by {@code uq_task_attempt}.
     */
    @Min(value = 1, message = "attemptNumber must be >= 1")
    @Column(name = "attempt_number", nullable = false, updatable = false)
    private int attemptNumber;

    /**
     * ID of the worker that executed this attempt.
     */
    @Size(max = 100)
    @Column(name = "worker_id", length = 100)
    private String workerId;

    /** When the worker began executing the task handler. */
    @NotNull
    @Column(name = "started_at", nullable = false, updatable = false,
            columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime startedAt;

    /** When this attempt concluded (null if the worker crashed mid-execution). */
    @Column(name = "completed_at", columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime completedAt;

    /**
     * Outcome of this attempt. Expected values: {@code "SUCCESS"}, {@code "FAILED"}.
     * Stored as VARCHAR to remain decoupled from the task's lifecycle {@link TaskStatus}.
     */
    @NotBlank
    @Size(max = 20)
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    /** Error message captured if this attempt failed. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Duration of this attempt in milliseconds. */
    @Column(name = "duration_ms")
    private Long durationMs;

    // -------------------------------------------------------------------------
    // Getters and setters
    // -------------------------------------------------------------------------

    public UUID getId() { return id; }

    public Task getTask() { return task; }
    public void setTask(Task task) { this.task = task; }

    public int getAttemptNumber() { return attemptNumber; }
    public void setAttemptNumber(int attemptNumber) { this.attemptNumber = attemptNumber; }

    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

    public OffsetDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(OffsetDateTime startedAt) { this.startedAt = startedAt; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    @Override
    public String toString() {
        return "TaskAttempt{id=" + id + ", taskId=" + (task != null ? task.getId() : null) +
               ", attemptNumber=" + attemptNumber + ", status='" + status + "'}";
    }
}
