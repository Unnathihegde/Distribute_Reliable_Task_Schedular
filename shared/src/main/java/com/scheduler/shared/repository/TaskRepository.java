package com.scheduler.shared.repository;

import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Task} entities.
 *
 * <h2>Critical method: {@link #transitionStatus}</h2>
 *
 * <p>This repository's central purpose is to expose the compare-and-swap (CAS)
 * UPDATE query that makes all state transitions safe under concurrency. The query is:
 *
 * <pre>{@code
 * UPDATE tasks
 *    SET status     = :newStatus,
 *        updated_at = now()
 *  WHERE id         = :taskId
 *    AND status     = :expectedStatus      ← the CAS predicate
 * }</pre>
 *
 * <p>The {@code AND status = :expectedStatus} clause is the entire concurrency-safety
 * argument. If two threads both read a task in {@code SCHEDULED} state and both try
 * to transition it to {@code QUEUED}:
 * <ul>
 *   <li>Thread A's UPDATE matches the row (status is still SCHEDULED) → 1 row updated.</li>
 *   <li>Thread B's UPDATE finds status is now QUEUED → predicate fails → 0 rows updated.</li>
 * </ul>
 *
 * <p>The database engine evaluates the WHERE clause atomically. There is no
 * application-level read before the write — doing so (a read-check-then-write pattern)
 * would reintroduce the race condition this design explicitly prevents.
 *
 * <p>{@link org.springframework.transaction.annotation.Transactional} with
 * {@code readOnly = false} is required for {@code @Modifying} queries. The annotation
 * is placed here so callers don't need to wrap every transition in a transaction;
 * if a caller already has a transaction, Spring participates in it (default propagation
 * is {@code REQUIRED}).
 *
 * <h2>Pagination queries</h2>
 * <p>Keyset (cursor) pagination queries use the {@code (createdAt, id)} composite key.
 * See {@code TaskService} for cursor encoding/decoding.
 */
public interface TaskRepository extends JpaRepository<Task, UUID> {

    // -------------------------------------------------------------------------
    // CAS state transition — the core concurrency primitive
    // -------------------------------------------------------------------------

    /**
     * Atomically transitions a task from {@code expectedStatus} to {@code newStatus}
     * using a compare-and-swap UPDATE at the database level.
     *
     * <p>This is the <strong>only</strong> correct way to change a task's status in
     * production code. Direct field mutation followed by {@code save()} is NOT safe
     * under concurrency and MUST NOT be used for status transitions.
     *
     * @param taskId         the task to transition
     * @param expectedStatus the status the task must currently be in for the update to apply
     * @param newStatus      the status to transition to
     * @return 1 if the transition succeeded; 0 if the task was not in
     *         {@code expectedStatus} (CAS miss — another process already transitioned it)
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE Task t
               SET t.status    = :newStatus,
                   t.updatedAt = CURRENT_TIMESTAMP
             WHERE t.id        = :taskId
               AND t.status    = :expectedStatus
            """)
    int transitionStatus(@Param("taskId") UUID taskId,
                         @Param("expectedStatus") TaskStatus expectedStatus,
                         @Param("newStatus") TaskStatus newStatus);

    // -------------------------------------------------------------------------
    // Lookup helpers used by the API and state machine
    // -------------------------------------------------------------------------

    /**
     * Finds a task by its idempotency key. Used at submission time to detect
     * duplicate requests from clients retrying their POST.
     *
     * @param idempotencyKey the key provided by the client
     * @return the existing task, if any
     */
    Optional<Task> findByIdempotencyKey(String idempotencyKey);

    // -------------------------------------------------------------------------
    // Listing / filtering — first page (no cursor)
    // -------------------------------------------------------------------------

    /** Returns tasks with the given status, ordered by {@code (createdAt DESC, id ASC)}. */
    List<Task> findByStatus(TaskStatus status, Pageable pageable);

    /** Returns tasks with the given task type, ordered by {@code (createdAt DESC, id ASC)}. */
    List<Task> findByTaskType(String taskType, Pageable pageable);

    /** Returns tasks matching both status and task type. */
    List<Task> findByStatusAndTaskType(TaskStatus status, String taskType, Pageable pageable);

    // -------------------------------------------------------------------------
    // Listing / filtering — subsequent pages (keyset cursor)
    // Cursor: (createdAt, id) — composite key for stable ordering.
    // Predicate: row is "after" cursor if createdAt < cursor.createdAt
    //   OR (createdAt = cursor.createdAt AND id > cursor.id).
    // This matches ORDER BY created_at DESC, id ASC.
    // -------------------------------------------------------------------------

    @Query("""
            SELECT t FROM Task t
             WHERE (t.createdAt < :cursorCreatedAt)
                OR (t.createdAt = :cursorCreatedAt AND t.id > :cursorId)
             ORDER BY t.createdAt DESC, t.id ASC
            """)
    List<Task> findAllWithCursor(
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @Query("""
            SELECT t FROM Task t
             WHERE t.status = :status
               AND ((t.createdAt < :cursorCreatedAt)
                OR  (t.createdAt = :cursorCreatedAt AND t.id > :cursorId))
             ORDER BY t.createdAt DESC, t.id ASC
            """)
    List<Task> findByStatusWithCursor(
            @Param("status") TaskStatus status,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @Query("""
            SELECT t FROM Task t
             WHERE t.taskType = :taskType
               AND ((t.createdAt < :cursorCreatedAt)
                OR  (t.createdAt = :cursorCreatedAt AND t.id > :cursorId))
             ORDER BY t.createdAt DESC, t.id ASC
            """)
    List<Task> findByTypeWithCursor(
            @Param("taskType") String taskType,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @Query("""
            SELECT t FROM Task t
             WHERE t.status = :status
               AND t.taskType = :taskType
               AND ((t.createdAt < :cursorCreatedAt)
                OR  (t.createdAt = :cursorCreatedAt AND t.id > :cursorId))
             ORDER BY t.createdAt DESC, t.id ASC
            """)
    List<Task> findByStatusAndTypeWithCursor(
            @Param("status") TaskStatus status,
            @Param("taskType") String taskType,
            @Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    // -------------------------------------------------------------------------
    // Scheduler-phase helpers (used by scheduler service)
    // -------------------------------------------------------------------------

    /**
     * Finds SCHEDULED tasks whose {@code scheduled_at} has arrived, up to {@code limit} rows.
     * Used by the scheduler dispatch loop.
     */
    @Query("""
            SELECT t FROM Task t
             WHERE t.status = 'SCHEDULED'
               AND t.scheduledAt <= CURRENT_TIMESTAMP
             ORDER BY t.priority ASC, t.scheduledAt ASC
            """)
    List<Task> findReadyToDispatch(Pageable pageable);

    /**
     * Finds tasks in RETRY_WAIT whose {@code next_retry_at} has arrived.
     */
    @Query("""
            SELECT t FROM Task t
             WHERE t.status = 'RETRY_WAIT'
               AND t.nextRetryAt <= CURRENT_TIMESTAMP
            """)
    List<Task> findReadyForRetry(Pageable pageable);

    /**
     * Counts tasks by status — used by health/metrics endpoints.
     */
    long countByStatus(TaskStatus status);

    // -------------------------------------------------------------------------
    // Scheduler recovery helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the count of tasks in a given status, used for monitoring.
     */
    @Query("SELECT COUNT(t) FROM Task t WHERE t.status = :status")
    long countTasksByStatus(@Param("status") TaskStatus status);
}
