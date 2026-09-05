package com.scheduler.shared.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit trail record representing a single execution attempt of a {@link Task}.
 *
 * <p>Persisted to {@code task_attempts} table. Provides complete execution history,
 * execution duration, worker identity, and error messages for observability.</p>
 */
@Entity
@Table(name = "task_attempts", uniqueConstraints = {
    @UniqueConstraint(name = "uq_task_attempt", columnNames = {"task_id", "attempt_number"})
})
public class TaskAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status; // SUCCESS, FAILED

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected TaskAttempt() {
        // JPA standard constructor
    }

    public TaskAttempt(UUID taskId, int attemptNumber, String workerId, Instant startedAt, Instant completedAt, String status, String errorMessage, Long durationMs) {
        this.taskId = taskId;
        this.attemptNumber = attemptNumber;
        this.workerId = workerId;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.status = status;
        this.errorMessage = errorMessage;
        this.durationMs = durationMs;
    }

    public UUID getId() { return id; }
    public UUID getTaskId() { return taskId; }
    public int getAttemptNumber() { return attemptNumber; }
    public String getWorkerId() { return workerId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public String getStatus() { return status; }
    public String getErrorMessage() { return errorMessage; }
    public Long getDurationMs() { return durationMs; }
}
