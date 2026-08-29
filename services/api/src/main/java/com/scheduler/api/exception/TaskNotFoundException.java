package com.scheduler.api.exception;

import java.util.UUID;

/**
 * Thrown when a task with the requested ID does not exist in the database.
 * Maps to HTTP 404 Not Found via {@link GlobalExceptionHandler}.
 */
public class TaskNotFoundException extends RuntimeException {

    private final UUID taskId;

    public TaskNotFoundException(UUID taskId) {
        super("Task not found: " + taskId);
        this.taskId = taskId;
    }

    public UUID getTaskId() { return taskId; }
}
