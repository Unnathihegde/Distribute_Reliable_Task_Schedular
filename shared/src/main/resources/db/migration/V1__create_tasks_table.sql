-- =============================================================================
-- V1: Create tasks table — the core task metadata and lifecycle state store
-- =============================================================================
-- PostgreSQL is the single source of truth for all task state.
-- Every state transition is an atomic conditional UPDATE on this table.
-- =============================================================================

CREATE TABLE tasks (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type          VARCHAR(50)  NOT NULL,                              -- EMAIL, HTTP, DEMO
    payload            JSONB        NOT NULL,                              -- task-specific data
    status             VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',
    priority           VARCHAR(10)  NOT NULL DEFAULT 'MEDIUM',            -- HIGH, MEDIUM, LOW

    -- Scheduling
    scheduled_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Retry
    attempt_count      INT          NOT NULL DEFAULT 0,
    max_attempts       INT          NOT NULL DEFAULT 5,
    next_retry_at      TIMESTAMPTZ,

    -- Lease (worker crash recovery)
    worker_id          VARCHAR(100),
    lease_id           UUID,
    lease_expires_at   TIMESTAMPTZ,

    -- Idempotency
    idempotency_key    VARCHAR(255),

    -- Error tracking
    last_error         TEXT,

    -- Recurrence
    cron_expression    VARCHAR(100),
    recurrence_enabled BOOLEAN      NOT NULL DEFAULT false,
    parent_task_id     UUID         REFERENCES tasks(id),

    -- Audit
    created_by         VARCHAR(100),

    -- Constraints
    CONSTRAINT chk_status CHECK (status IN (
        'SCHEDULED', 'QUEUED', 'RUNNING', 'SUCCESS',
        'RETRY_WAIT', 'FAILED', 'DEAD_LETTER', 'CANCELLED'
    )),
    CONSTRAINT chk_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT chk_max_attempts CHECK (max_attempts >= 1 AND max_attempts <= 20)
);

-- =============================================================================
-- Indexes — each serves a specific query pattern
-- =============================================================================

-- Scheduler polling: find due tasks efficiently
-- Partial index on status='SCHEDULED' keeps the index small (most tasks are terminal)
CREATE INDEX idx_tasks_scheduler_poll
    ON tasks (scheduled_at)
    WHERE status = 'SCHEDULED';

-- Lease recovery: find stuck RUNNING tasks with expired leases
-- Partial index on status='RUNNING' — only a small fraction of tasks are running
CREATE INDEX idx_tasks_lease_recovery
    ON tasks (lease_expires_at)
    WHERE status = 'RUNNING';

-- Retry recovery: find tasks ready for their next retry attempt
-- Partial index on status='RETRY_WAIT'
CREATE INDEX idx_tasks_retry
    ON tasks (next_retry_at)
    WHERE status = 'RETRY_WAIT';

-- Idempotency: fast duplicate detection on submission
-- Unique + partial (only non-null keys) to allow multiple tasks without keys
CREATE UNIQUE INDEX idx_tasks_idempotency_key
    ON tasks (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- API queries: list tasks by status with newest first
CREATE INDEX idx_tasks_status
    ON tasks (status, created_at DESC);

-- API queries: list tasks by type with newest first
CREATE INDEX idx_tasks_type
    ON tasks (task_type, created_at DESC);
