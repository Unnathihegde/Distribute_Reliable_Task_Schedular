package com.scheduler.worker.handler;

import com.scheduler.shared.domain.Task;

/**
 * Strategy interface for task type handlers.
 */
public interface TaskHandler {

    /** Returns the unique task type string this handler processes (e.g. "DEMO", "EMAIL"). */
    String getTaskType();

    /** Executes the task. Throws exception on failure. */
    void execute(Task task) throws Exception;
}
