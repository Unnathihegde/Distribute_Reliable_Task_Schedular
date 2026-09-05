package com.scheduler.shared.statemachine;

import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.exception.TaskNotFoundException;
import com.scheduler.shared.repository.TaskRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Governs all task lifecycle state transitions.
 *
 * <h3>Design</h3>
 * <p>All mutations go through this class — never raw {@code task.setStatus(...)} calls
 * from service code. This centralises transition validation and prevents illegal states
 * from entering the database.</p>
 *
 * <h3>Concurrency</h3>
 * <p>Transitions are performed inside a transaction. For high-concurrency operations
 * (scheduler claiming, worker completion), the scheduler and worker use explicit
 * {@code UPDATE ... WHERE status = 'expected'} queries (compare-and-swap) rather
 * than this class, to avoid lost-update races under concurrent load. This class is
 * used for client-initiated operations (cancel) where throughput is lower.</p>
 *
 * <h3>Cancellable states</h3>
 * <p>Only SCHEDULED and QUEUED tasks can be cancelled. RUNNING/terminal tasks cannot,
 * because the worker already holds a lease and aborting mid-execution is unsafe without
 * a cooperative cancellation protocol (deferred to future work).</p>
 */
@Component
public class TaskStateMachine {

    /** States from which cancellation is permitted. */
    private static final Set<TaskStatus> CANCELLABLE_STATES =
        EnumSet.of(TaskStatus.SCHEDULED, TaskStatus.QUEUED);

    private final TaskRepository taskRepository;

    public TaskStateMachine(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /**
     * Cancels a task if it is in a cancellable state.
     *
     * @param taskId the task to cancel
     * @return the updated task
     * @throws TaskNotFoundException           if the task does not exist
     * @throws IllegalStateTransitionException if the task cannot be cancelled
     */
    @Transactional
    public Task cancel(UUID taskId) {
        Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));

        TaskStatus current = task.getStatus();
        if (!CANCELLABLE_STATES.contains(current)) {
            throw new IllegalStateTransitionException(taskId, current, TaskStatus.CANCELLED);
        }

        task.setStatus(TaskStatus.CANCELLED);
        task.setCompletedAt(Instant.now());
        return taskRepository.save(task);
    }

    /**
     * Validates that a transition from {@code from} to {@code to} is legal,
     * without persisting anything. Useful for pre-flight checks in the scheduler/worker.
     *
     * @throws IllegalStateTransitionException if the transition is not permitted
     */
    public void assertTransitionAllowed(UUID taskId, TaskStatus from, TaskStatus to) {
        if (!isTransitionAllowed(from, to)) {
            throw new IllegalStateTransitionException(taskId, from, to);
        }
    }

    /**
     * Returns {@code true} if the transition {@code from → to} is a valid arc in the
     * state machine.
     */
    public boolean isTransitionAllowed(TaskStatus from, TaskStatus to) {
        return switch (from) {
            case SCHEDULED   -> to == TaskStatus.QUEUED     || to == TaskStatus.CANCELLED;
            case QUEUED      -> to == TaskStatus.RUNNING    || to == TaskStatus.CANCELLED  || to == TaskStatus.SCHEDULED;
            case RUNNING     -> to == TaskStatus.SUCCESS    || to == TaskStatus.FAILED     || to == TaskStatus.RETRY_WAIT
                                                            || to == TaskStatus.QUEUED;
            case RETRY_WAIT  -> to == TaskStatus.SCHEDULED;
            case FAILED      -> to == TaskStatus.DEAD_LETTER;
            case DEAD_LETTER -> to == TaskStatus.SCHEDULED;
            // Terminal states — no outgoing transitions
            case SUCCESS, CANCELLED -> false;
        };
    }
}
