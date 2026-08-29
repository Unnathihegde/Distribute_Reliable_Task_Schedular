package com.scheduler.shared.domain;

/**
 * Execution priority for a {@link Task}.
 *
 * <p>The scheduler uses priority to order dispatch — higher-priority tasks are
 * published first and routed to dedicated RabbitMQ queues ({@code task.high},
 * {@code task.medium}, {@code task.low}).
 *
 * <p>Matches the {@code chk_priority} database CHECK constraint:
 * {@code priority IN ('HIGH', 'MEDIUM', 'LOW')}.
 */
public enum Priority {

    /** Highest urgency. Dispatched before MEDIUM and LOW tasks. */
    HIGH,

    /** Standard urgency. Default for tasks that do not specify a priority. */
    MEDIUM,

    /** Background / best-effort. Dispatched only after HIGH and MEDIUM are drained. */
    LOW
}
