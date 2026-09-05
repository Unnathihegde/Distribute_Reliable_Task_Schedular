-- =============================================================================
-- V4: Add traceparent column to tasks table for distributed tracing context
-- =============================================================================

ALTER TABLE tasks ADD COLUMN traceparent VARCHAR(255);
