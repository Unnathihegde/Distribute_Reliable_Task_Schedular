package com.scheduler.shared.statemachine;

import com.scheduler.shared.domain.TaskStatus;
import java.util.UUID;

/**
 * Thrown when a requested state transition is illegal given the task's current status.
 *
 * <p>This is a domain-level exception, not a persistence error. It is raised by
 * {@link TaskStateMachine} when a caller attempts a transition that is not permitted
 * (e.g., cancelling a task that is already in SUCCESS state).</p>
 *
 * <p>Maps to HTTP 409 Conflict in the API layer.</p>
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final UUID taskId;
    private final TaskStatus currentStatus;
    private final TaskStatus requestedStatus;

    public IllegalStateTransitionException(UUID taskId, TaskStatus current, TaskStatus requested) {
        super(String.format(
            "Task %s is in status %s and cannot transition to %s.",
            taskId, current, requested
        ));
        this.taskId = taskId;
        this.currentStatus = current;
        this.requestedStatus = requested;
    }

    public UUID getTaskId() { return taskId; }
    public TaskStatus getCurrentStatus() { return currentStatus; }
    public TaskStatus getRequestedStatus() { return requestedStatus; }
}
