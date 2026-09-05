package com.scheduler.shared.repository;

import com.scheduler.shared.domain.Task;
import com.scheduler.shared.domain.TaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA repository for {@link Task} entities.
 *
 * <p>Standard CRUD is inherited from {@link JpaRepository}. Custom queries are
 * declared here with JPQL or native SQL where the ORM abstraction would hide
 * important semantics (e.g., keyset pagination, scheduler polling).</p>
 */
public interface TaskRepository extends JpaRepository<Task, UUID> {

    // -------------------------------------------------------------------------
    // Idempotency
    // -------------------------------------------------------------------------

    /**
     * Looks up a task by its client-supplied idempotency key.
     * Used before insert to return existing tasks without re-creating them.
     */
    Optional<Task> findByIdempotencyKey(String idempotencyKey);

    /**
     * Counts active (non-soft-deleted) tasks with the given status.
     */
    long countByStatus(TaskStatus status);

    // -------------------------------------------------------------------------
    // API list queries (cursor/keyset pagination)
    // -------------------------------------------------------------------------

    /**
     * First page: no cursor. Returns tasks ordered by (createdAt DESC, id ASC),
     * optionally filtered by status.
     *
     * <p>Fetches {@code limit + 1} rows so the caller can determine whether a
     * next page exists without a separate COUNT query.</p>
     */
    @Query("""
        SELECT t FROM Task t
        WHERE (:status IS NULL OR t.status = :status)
          AND t.deletedAt IS NULL
        ORDER BY t.createdAt DESC, t.id ASC
        """)
    List<Task> findFirstPage(
        @Param("status") TaskStatus status,
        Pageable pageable
    );

    @Query("""
        SELECT t FROM Task t
        WHERE (:status IS NULL OR t.status = :status)
          AND t.deletedAt IS NULL
          AND (t.createdAt < :cursorCreatedAt
               OR (t.createdAt = :cursorCreatedAt AND t.id > :cursorId))
        ORDER BY t.createdAt DESC, t.id ASC
        """)
    List<Task> findNextPage(
        @Param("status") TaskStatus status,
        @Param("cursorCreatedAt") Instant cursorCreatedAt,
        @Param("cursorId") UUID cursorId,
        Pageable pageable
    );

    // -------------------------------------------------------------------------
    // Scheduler polling and recovery queries (native SQL for FOR UPDATE SKIP LOCKED)
    // -------------------------------------------------------------------------

    /**
     * Claims a batch of due SCHEDULED tasks, ordered by priority (HIGH, MEDIUM, LOW)
     * and scheduled_at ASC. Uses FOR UPDATE SKIP LOCKED for concurrent safety.
     */
    @Query(value = """
        SELECT * FROM tasks
        WHERE status = 'SCHEDULED' AND scheduled_at <= now() AND deleted_at IS NULL
        ORDER BY CASE priority WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 WHEN 'LOW' THEN 3 END,
                 scheduled_at ASC
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Task> claimScheduledTasks(@Param("batchSize") int batchSize);

    /**
     * Finds tasks in RETRY_WAIT status whose retry delay has expired.
     * Uses FOR UPDATE SKIP LOCKED to prevent concurrent recovery sweeps from conflicting.
     */
    @Query(value = """
        SELECT * FROM tasks
        WHERE status = 'RETRY_WAIT' AND next_retry_at <= now() AND deleted_at IS NULL
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Task> findRetryWaitTasksDue();

    /**
     * Finds tasks in RUNNING status whose worker lease has expired (lease_expires_at < now()).
     * Uses FOR UPDATE SKIP LOCKED to prevent concurrent recovery sweeps from conflicting.
     */
    @Query(value = """
        SELECT * FROM tasks
        WHERE status = 'RUNNING' AND lease_expires_at < now() AND deleted_at IS NULL
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Task> findExpiredLeaseTasks();

    /**
     * Finds tasks in QUEUED status that have been stuck longer than the staleness threshold.
     * Uses FOR UPDATE SKIP LOCKED to prevent concurrent recovery sweeps from conflicting.
     */
    @Query(value = """
        SELECT * FROM tasks
        WHERE status = 'QUEUED' AND updated_at < :threshold AND deleted_at IS NULL
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<Task> findStaleQueuedTasks(@Param("threshold") Instant threshold);

    // -------------------------------------------------------------------------
    // Worker Compare-And-Swap (CAS) state transitions
    // -------------------------------------------------------------------------

    /**
     * Atomically claims a task from QUEUED -> RUNNING and assigns worker lease.
     * Returns 1 if successful, 0 if task was not in QUEUED state.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE Task t
        SET t.status = com.scheduler.shared.domain.TaskStatus.RUNNING,
            t.workerId = :workerId,
            t.leaseId = :leaseId,
            t.leaseExpiresAt = :leaseExpiresAt,
            t.startedAt = :startedAt,
            t.attemptCount = t.attemptCount + 1
        WHERE t.id = :taskId AND t.status = com.scheduler.shared.domain.TaskStatus.QUEUED
        """)
    int claimLeaseQueuedToRunning(
        @Param("taskId") UUID taskId,
        @Param("workerId") String workerId,
        @Param("leaseId") UUID leaseId,
        @Param("leaseExpiresAt") Instant leaseExpiresAt,
        @Param("startedAt") Instant startedAt
    );

    /**
     * Atomically transitions a task from RUNNING -> SUCCESS.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE Task t
        SET t.status = com.scheduler.shared.domain.TaskStatus.SUCCESS,
            t.completedAt = :completedAt
        WHERE t.id = :taskId AND t.status = com.scheduler.shared.domain.TaskStatus.RUNNING AND t.leaseId = :leaseId
        """)
    int markSuccess(
        @Param("taskId") UUID taskId,
        @Param("leaseId") UUID leaseId,
        @Param("completedAt") Instant completedAt
    );

    /**
     * Atomically transitions a task from RUNNING -> RETRY_WAIT with next retry timestamp and last error message.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE Task t
        SET t.status = com.scheduler.shared.domain.TaskStatus.RETRY_WAIT,
            t.nextRetryAt = :nextRetryAt,
            t.lastError = :lastError
        WHERE t.id = :taskId AND t.status = com.scheduler.shared.domain.TaskStatus.RUNNING AND t.leaseId = :leaseId
        """)
    int markRetryWait(
        @Param("taskId") UUID taskId,
        @Param("leaseId") UUID leaseId,
        @Param("nextRetryAt") Instant nextRetryAt,
        @Param("lastError") String lastError
    );

    /**
     * Atomically transitions a task from RUNNING -> DEAD_LETTER with error message and completion timestamp.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("""
        UPDATE Task t
        SET t.status = com.scheduler.shared.domain.TaskStatus.DEAD_LETTER,
            t.completedAt = :completedAt,
            t.lastError = :lastError
        WHERE t.id = :taskId AND t.status = com.scheduler.shared.domain.TaskStatus.RUNNING AND t.leaseId = :leaseId
        """)
    int markDeadLetter(
        @Param("taskId") UUID taskId,
        @Param("leaseId") UUID leaseId,
        @Param("completedAt") Instant completedAt,
        @Param("lastError") String lastError
    );
}


