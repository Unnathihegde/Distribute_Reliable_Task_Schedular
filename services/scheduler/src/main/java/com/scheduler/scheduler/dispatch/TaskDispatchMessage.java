package com.scheduler.scheduler.dispatch;

import com.scheduler.shared.domain.Priority;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight message payload published to RabbitMQ.
 *
 * <p>Contains minimal task identity metadata. Workers fetch the full payload
 * directly from PostgreSQL upon receipt, ensuring single source of truth.</p>
 */
public class TaskDispatchMessage {

    private UUID taskId;
    private String taskType;
    private Priority priority;
    private int attemptCount;
    private Instant dispatchedAt;

    public TaskDispatchMessage() {
    }

    public TaskDispatchMessage(UUID taskId, String taskType, Priority priority, int attemptCount, Instant dispatchedAt) {
        this.taskId = taskId;
        this.taskType = taskType;
        this.priority = priority;
        this.attemptCount = attemptCount;
        this.dispatchedAt = dispatchedAt;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public void setTaskType(String taskType) {
        this.taskType = taskType;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }
}
