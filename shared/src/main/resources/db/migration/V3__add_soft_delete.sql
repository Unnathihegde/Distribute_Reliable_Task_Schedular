-- =============================================================================
-- V3: Add soft delete column and index to tasks table
-- =============================================================================

ALTER TABLE tasks ADD COLUMN deleted_at TIMESTAMPTZ;

-- Partial index for active tasks vs soft-deleted tasks
CREATE INDEX idx_tasks_deleted_at
    ON tasks (deleted_at)
    WHERE deleted_at IS NOT NULL;
