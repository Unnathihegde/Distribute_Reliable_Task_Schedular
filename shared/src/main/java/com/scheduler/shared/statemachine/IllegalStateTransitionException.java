package com.scheduler.shared.statemachine;

import com.scheduler.shared.domain.TaskStatus;

import java.util.UUID;

/**
 * Thrown when a requested state transition is not legal according to the
 * task state machine defined in blueprint Section 7, or when the database-level
 * compare-and-swap (CAS) UPDATE returns 0 rows (meaning another process already
 * transitioned the task away from the expected state).
 *
 * <p>This is an unchecked exception. Callers that need to handle it (e.g., the
 * Worker service deciding whether to NACK a RabbitMQ message) should catch it
 * explicitly.
 *
 * <p>Two distinct failure modes produce this exception:
 * <ol>
 *   <li><strong>Invalid transition</strong> — the requested {@code (from, to)} pair
 *       is not in the legal transition table (checked before hitting the DB).</li>
 *   <li><strong>CAS miss</strong> — the transition pair is valid but the DB
 *       {@code UPDATE ... WHERE status = :expected} matched 0 rows, meaning the task
 *       was already moved by another concurrent process. This is the safety net for
 *       the race condition described in blueprint Section 7.</li>
 * </ol>
 */
public class IllegalStateTransitionException extends RuntimeException {

    private final UUID taskId;
    private final TaskStatus from;
    private final TaskStatus to;

    /**
     * Constructs an exception for an invalid or failed state transition.
     *
     * @param taskId the ID of the task involved (may be null for in-memory validation)
     * @param from   the state the task was expected to be in
     * @param to     the state that was requested
     * @param reason human-readable explanation (INVALID_TRANSITION or CAS_MISS)
     */
    public IllegalStateTransitionException(UUID taskId, TaskStatus from, TaskStatus to,
                                           String reason) {
        super(String.format(
            "Illegal state transition for task %s: %s → %s. Reason: %s",
            taskId, from, to, reason
        ));
        this.taskId = taskId;
        this.from = from;
        this.to = to;
    }

    /**
     * Convenience constructor for in-memory validation failures (no task ID yet).
     */
    public IllegalStateTransitionException(TaskStatus from, TaskStatus to) {
        this(null, from, to, "INVALID_TRANSITION — not in legal transition table");
    }

    /** The task ID that was involved, or {@code null} if this was an in-memory check. */
    public UUID getTaskId() { return taskId; }

    /** The state the transition was attempted from. */
    public TaskStatus getFrom() { return from; }

    /** The state the transition was attempted to. */
    public TaskStatus getTo() { return to; }
}
