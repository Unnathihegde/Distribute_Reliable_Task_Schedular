package com.scheduler.shared.exception;

import java.util.UUID;

/**
 * Thrown when a requested task does not exist in the database.
 */
public class TaskNotFoundException extends RuntimeException {

    private final UUID taskId;

    public TaskNotFoundException(UUID taskId) {
        super("Task not found: " + taskId);
        this.taskId = taskId;
    }

    public UUID getTaskId() {
        return taskId;
    }
}
