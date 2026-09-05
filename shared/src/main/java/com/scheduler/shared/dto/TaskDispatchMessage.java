package com.scheduler.shared.dto;

import com.scheduler.shared.domain.Priority;

import java.io.Serializable;
import java.util.UUID;

/**
 * Lightweight RabbitMQ message payload published by Scheduler Service and consumed by Worker Service.
 */
public class TaskDispatchMessage implements Serializable {

    private UUID taskId;
    private Priority priority;
    private String taskType;

    public TaskDispatchMessage() {
        // Jackson default constructor
    }

    public TaskDispatchMessage(UUID taskId, Priority priority, String taskType) {
        this.taskId = taskId;
        this.priority = priority;
        this.taskType = taskType;
    }

    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID taskId) { this.taskId = taskId; }

    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
}
