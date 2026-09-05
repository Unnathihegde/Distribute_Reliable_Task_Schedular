package com.scheduler.shared.domain;

/**
 * Lifecycle states of a Task.
 *
 * <p>State machine (valid transitions):</p>
 * <pre>
 *   SCHEDULED  → QUEUED      (scheduler claims task)
 *   SCHEDULED  → CANCELLED   (client cancels before dispatch)
 *   QUEUED     → RUNNING     (worker picks up task)
 *   QUEUED     → CANCELLED   (client cancels before execution)
 *   RUNNING    → SUCCESS     (worker completes successfully)
 *   RUNNING    → FAILED      (worker reports execution failure)
 *   RUNNING    → RETRY_WAIT  (worker reports transient failure, retries remain)
 *   RETRY_WAIT → QUEUED      (backoff elapsed, re-queued)
 *   FAILED     → DEAD_LETTER (retry exhaustion — permanently failed)
 * </pre>
 *
 * <p>Terminal states: SUCCESS, CANCELLED, DEAD_LETTER</p>
 */
public enum TaskStatus {
    /** Task is persisted and waiting for the scheduler to dispatch it. */
    SCHEDULED,

    /** Task has been dispatched to the message queue; waiting for a worker to claim it. */
    QUEUED,

    /** A worker has claimed the task and is actively executing it. */
    RUNNING,

    /** Execution completed successfully. Terminal state. */
    SUCCESS,

    /** Execution failed; the task is in a backoff window before the next retry attempt. */
    RETRY_WAIT,

    /** Execution failed permanently (all retry attempts exhausted, or unrecoverable error). */
    FAILED,

    /**
     * Task exceeded its maximum retry attempts and has been moved to the dead-letter
     * bucket for manual inspection. Terminal state.
     */
    DEAD_LETTER,

    /** Client explicitly cancelled the task before execution completed. Terminal state. */
    CANCELLED
}
