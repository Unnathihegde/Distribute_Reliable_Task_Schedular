package com.scheduler.shared.domain;

/**
 * Execution priority of a Task.
 *
 * <p>The scheduler uses priority to order dispatch when multiple tasks become
 * eligible simultaneously. HIGH tasks are dispatched before MEDIUM before LOW.</p>
 */
public enum Priority {
    HIGH,
    MEDIUM,
    LOW
}
