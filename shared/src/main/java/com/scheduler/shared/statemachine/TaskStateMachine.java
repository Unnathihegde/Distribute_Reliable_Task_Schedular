package com.scheduler.shared.statemachine;

import com.scheduler.shared.domain.TaskStatus;
import com.scheduler.shared.repository.TaskRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Encodes all legal task state transitions from blueprint Section 7 and enforces
 * them via a database-level compare-and-swap (CAS) UPDATE.
 *
 * <h2>Design</h2>
 *
 * <p>The transition table is a static {@code Map<TaskStatus, Set<TaskStatus>>} —
 * the key is the "from" state and the value is the set of allowed "to" states.
 * This makes legal/illegal transitions immediately visible in code and eliminates
 * scattered if-else chains across the codebase.
 *
 * <h2>Two public methods</h2>
 * <ul>
 *   <li>{@link #canTransition(TaskStatus, TaskStatus)} — pure in-memory predicate.
 *       No database call. Safe to use for pre-flight validation, unit tests, and
 *       building UI that shows what actions are available.</li>
 *   <li>{@link #transition(UUID, TaskStatus, TaskStatus)} — the real enforcement
 *       path. Calls {@link TaskRepository#transitionStatus}, which issues a single
 *       atomic {@code UPDATE ... WHERE status = :expected}. If the DB returns 0 rows,
 *       the task was already moved by another process → {@link IllegalStateTransitionException}
 *       is thrown. No read before the write.</li>
 * </ul>
 *
 * <h2>CAS and race-condition prevention</h2>
 *
 * <p>Without the database-level CAS, two concurrent processes (e.g., two scheduler
 * instances) could both read a task in SCHEDULED, both pass the application-level
 * canTransition check, and both issue an UPDATE to QUEUED — resulting in double
 * dispatch. The {@code WHERE status = :expected} predicate prevents this because
 * the database evaluates it atomically: only one of the two UPDATEs can match,
 * and the other sees 0 rows updated.
 *
 * @see TaskRepository#transitionStatus
 * @see IllegalStateTransitionException
 */
@Component
public class TaskStateMachine {

    // -------------------------------------------------------------------------
    // Legal transition table — encodes Section 7 of the blueprint
    // -------------------------------------------------------------------------

    /**
     * Every legal (from → to) pair. Any pair NOT in this map is illegal.
     *
     * <p>Transitions (12 total):
     * <pre>
     * SCHEDULED   → QUEUED        scheduler claims and dispatches
     * SCHEDULED   → CANCELLED     user cancellation
     *
     * QUEUED      → RUNNING       worker acquires lease
     * QUEUED      → CANCELLED     user cancellation
     * QUEUED      → SCHEDULED     lease-expiry recovery if stuck in QUEUED
     *
     * RUNNING     → SUCCESS       execution succeeded
     * RUNNING     → RETRY_WAIT    execution failed, retries remain
     * RUNNING     → FAILED        execution failed, no retries remain
     * RUNNING     → QUEUED        lease expired — recovery resets for re-dispatch
     *
     * RETRY_WAIT  → SCHEDULED     retry delay elapsed, re-enters scheduling
     *
     * FAILED      → DEAD_LETTER   automatic escalation
     *
     * DEAD_LETTER → SCHEDULED     manual retry by operator
     * </pre>
     *
     * <p>Explicitly illegal (enforced by absence from this map):
     * <ul>
     *   <li>{@code SUCCESS → *}  (terminal state)</li>
     *   <li>{@code CANCELLED → *}  (terminal state)</li>
     *   <li>{@code RUNNING → SCHEDULED}  (must go through RETRY_WAIT or lease-expiry)</li>
     *   <li>{@code QUEUED → SUCCESS}  (must go through RUNNING)</li>
     * </ul>
     */
    private static final Map<TaskStatus, Set<TaskStatus>> LEGAL_TRANSITIONS;

    static {
        Map<TaskStatus, Set<TaskStatus>> map = new EnumMap<>(TaskStatus.class);

        map.put(TaskStatus.SCHEDULED, EnumSet.of(
                TaskStatus.QUEUED,       // scheduler claims and dispatches
                TaskStatus.CANCELLED     // user cancellation
        ));

        map.put(TaskStatus.QUEUED, EnumSet.of(
                TaskStatus.RUNNING,      // worker acquires lease
                TaskStatus.CANCELLED,    // user cancellation
                TaskStatus.SCHEDULED     // lease-expiry recovery — reset for re-dispatch
        ));

        map.put(TaskStatus.RUNNING, EnumSet.of(
                TaskStatus.SUCCESS,      // execution succeeded
                TaskStatus.RETRY_WAIT,   // execution failed, retries remain
                TaskStatus.FAILED,       // execution failed, no retries remain
                TaskStatus.QUEUED        // lease expired — recovery resets for re-dispatch
        ));

        map.put(TaskStatus.RETRY_WAIT, EnumSet.of(
                TaskStatus.SCHEDULED     // retry delay elapsed, re-enters scheduling
        ));

        map.put(TaskStatus.FAILED, EnumSet.of(
                TaskStatus.DEAD_LETTER   // automatic escalation
        ));

        map.put(TaskStatus.DEAD_LETTER, EnumSet.of(
                TaskStatus.SCHEDULED     // manual retry by operator
        ));

        // SUCCESS and CANCELLED are terminal — no outgoing transitions.
        // They are intentionally absent from the map.

        LEGAL_TRANSITIONS = Collections.unmodifiableMap(map);
    }

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final TaskRepository taskRepository;

    /**
     * Constructor injection — required for testability (no Spring context needed
     * in unit tests; inject a mock repository directly).
     */
    public TaskStateMachine(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if transitioning from {@code from} to {@code to} is legal
     * according to the state machine table in blueprint Section 7.
     *
     * <p>This is a pure in-memory check. It does <strong>not</strong> consult the
     * database and does <strong>not</strong> perform the transition.
     *
     * @param from the current state
     * @param to   the desired next state
     * @return {@code true} if the transition is in the legal transition table
     */
    public boolean canTransition(TaskStatus from, TaskStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<TaskStatus> allowed = LEGAL_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Attempts to atomically transition task {@code taskId} from {@code from} to
     * {@code to} via a database-level compare-and-swap UPDATE.
     *
     * <p>Execution flow:
     * <ol>
     *   <li>Validates the transition against the legal transition table (fast,
     *       no DB call). Throws immediately if invalid.</li>
     *   <li>Issues {@code UPDATE tasks SET status = :to WHERE id = :taskId AND
     *       status = :from} via {@link TaskRepository#transitionStatus}.</li>
     *   <li>If the UPDATE returns 0 rows (CAS miss), throws
     *       {@link IllegalStateTransitionException} indicating that another concurrent
     *       process has already moved the task away from {@code from}.</li>
     * </ol>
     *
     * <p><strong>Why not just call {@code canTransition} and then {@code save()}?</strong>
     * Because between the application-level check and the subsequent write, another
     * process may have already changed the task's state. The CAS UPDATE pushes the
     * check into the DB engine, where it is evaluated atomically alongside the write.
     *
     * @param taskId the ID of the task to transition
     * @param from   the expected current status (CAS predicate)
     * @param to     the desired new status
     * @throws IllegalStateTransitionException if the transition is not legal, or if
     *         the DB CAS predicate matched 0 rows (concurrent transition by another process)
     */
    public void transition(UUID taskId, TaskStatus from, TaskStatus to) {
        // Step 1: fast pre-flight — reject obviously invalid transitions without
        // touching the database.
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(taskId, from, to,
                    "INVALID_TRANSITION — not in legal transition table");
        }

        // Step 2: CAS UPDATE. The WHERE clause is evaluated atomically by the DB.
        // Returns 1 on success, 0 if status != from (another process already transitioned).
        int rowsUpdated = taskRepository.transitionStatus(taskId, from, to);

        // Step 3: if 0 rows updated, the CAS missed — another process beat us to it.
        if (rowsUpdated == 0) {
            throw new IllegalStateTransitionException(taskId, from, to,
                    "CAS_MISS — task was not in expected status '" + from +
                    "' (likely transitioned concurrently by another process)");
        }
    }

    /**
     * Exposes the full legal transition table for inspection (e.g., API documentation,
     * admin endpoints). Returns an unmodifiable view.
     *
     * @return map of from-state to the set of reachable to-states
     */
    public Map<TaskStatus, Set<TaskStatus>> getLegalTransitions() {
        return LEGAL_TRANSITIONS;
    }
}
