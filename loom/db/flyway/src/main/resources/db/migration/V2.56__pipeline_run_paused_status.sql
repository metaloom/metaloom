-- Records the PAUSED status in the pipeline_run.status vocabulary.
--
-- The column is deliberately free-text (there is no CHECK constraint), so this is a
-- documentation-only change: no data migration is needed and existing rows are unaffected.
-- PAUSED is a non-terminal status - a paused run still holds a live engine and can be
-- resumed or cancelled.
COMMENT ON COLUMN pipeline_run.status IS 'Current status: PENDING, RUNNING, PAUSED, SUCCESS, FAILED, PARTIAL, CANCELLED';
