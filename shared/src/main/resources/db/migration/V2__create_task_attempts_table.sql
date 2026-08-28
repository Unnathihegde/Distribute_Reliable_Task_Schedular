-- =============================================================================
-- V2: Create task_attempts table — execution audit trail
-- =============================================================================
-- The tasks table tracks current state.
-- The task_attempts table provides a full history of every execution attempt,
-- essential for debugging, observability, and answering
-- "what happened to this task?"
-- =============================================================================

CREATE TABLE task_attempts (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id         UUID         NOT NULL REFERENCES tasks(id),
    attempt_number  INT          NOT NULL,
    worker_id       VARCHAR(100),
    started_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,
    status          VARCHAR(20)  NOT NULL,   -- SUCCESS, FAILED
    error_message   TEXT,
    duration_ms     BIGINT,

    CONSTRAINT uq_task_attempt UNIQUE (task_id, attempt_number)
);

-- Fast lookup of all attempts for a given task
CREATE INDEX idx_task_attempts_task_id ON task_attempts (task_id);
