package com.scheduler.shared.domain;

/**
 * All possible lifecycle states for a {@link Task}.
 *
 * <p>Legal transitions (enforced by {@code TaskStateMachine}):
 * <pre>
 * SCHEDULED   → QUEUED        (scheduler claims and dispatches)
 * SCHEDULED   → CANCELLED     (user cancellation)
 *
 * QUEUED      → RUNNING       (worker acquires lease)
 * QUEUED      → CANCELLED     (user cancellation)
 * QUEUED      → SCHEDULED     (lease-expiry recovery if stuck in QUEUED too long)
 *
 * RUNNING     → SUCCESS       (execution succeeded)
 * RUNNING     → RETRY_WAIT    (execution failed, retries remain)
 * RUNNING     → FAILED        (execution failed, no retries remain)
 * RUNNING     → QUEUED        (lease expired — recovery resets for re-dispatch)
 *
 * RETRY_WAIT  → SCHEDULED     (retry delay elapsed, re-enters scheduling)
 *
 * FAILED      → DEAD_LETTER   (automatic escalation)
 *
 * DEAD_LETTER → SCHEDULED     (manual retry by operator)
 * </pre>
 *
 * <p>Terminal states (no transitions out):
 * <ul>
 *   <li>{@link #SUCCESS}</li>
 *   <li>{@link #CANCELLED}</li>
 * </ul>
 *
 * @see com.scheduler.shared.statemachine.TaskStateMachine
 */
public enum TaskStatus {

    /**
     * Task has been persisted and is waiting for its {@code scheduled_at} time to arrive.
     * This is the initial state for every newly created task.
     */
    SCHEDULED,

    /**
     * The scheduler has claimed this task and published a dispatch message to RabbitMQ.
     * The task is waiting for a worker to pick it up.
     */
    QUEUED,

    /**
     * A worker has acquired a lease and is actively executing the task handler.
     */
    RUNNING,

    /**
     * Task execution completed successfully. <strong>Terminal state.</strong>
     */
    SUCCESS,

    /**
     * Task execution failed but retries remain. The task is waiting for its
     * {@code next_retry_at} time before being re-scheduled.
     */
    RETRY_WAIT,

    /**
     * Task execution failed and no retries remain. Will be automatically escalated
     * to {@link #DEAD_LETTER}.
     */
    FAILED,

    /**
     * Task has permanently failed. Isolated for operator inspection.
     * Can only transition back to {@link #SCHEDULED} via manual operator retry.
     */
    DEAD_LETTER,

    /**
     * Task was cancelled by the user. <strong>Terminal state.</strong>
     */
    CANCELLED
}
