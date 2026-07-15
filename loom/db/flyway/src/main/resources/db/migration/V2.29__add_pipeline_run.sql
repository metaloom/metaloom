-- Add pipeline run permissions to the loom_permission enum
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'CREATE_PIPELINE_RUN';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_PIPELINE_RUN';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'UPDATE_PIPELINE_RUN';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'DELETE_PIPELINE_RUN';

CREATE TABLE pipeline_run (
    uuid           UUID NOT NULL DEFAULT uuid_generate_v4(),
    pipeline_uuid  UUID NOT NULL,
    pipeline_version INTEGER NOT NULL DEFAULT 1,
    started        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    finished       TIMESTAMP WITHOUT TIME ZONE,
    status         VARCHAR NOT NULL DEFAULT 'PENDING',
    media_count    INTEGER NOT NULL DEFAULT 0,
    success_count  INTEGER NOT NULL DEFAULT 0,
    failure_count  INTEGER NOT NULL DEFAULT 0,
    skipped_count  INTEGER NOT NULL DEFAULT 0,
    dry_run        BOOLEAN NOT NULL DEFAULT false,
    error_message  VARCHAR,
    duration_ms    BIGINT,
    meta           JSONB,
    created        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    creator_uuid   UUID NOT NULL,
    edited         TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    editor_uuid    UUID NOT NULL,
    CONSTRAINT pipeline_run_pkey PRIMARY KEY (uuid),
    CONSTRAINT pipeline_run_pipeline_uuid_fkey FOREIGN KEY (pipeline_uuid) REFERENCES pipeline (uuid) ON DELETE CASCADE,
    CONSTRAINT pipeline_run_creator_uuid_fkey FOREIGN KEY (creator_uuid) REFERENCES "user" (uuid),
    CONSTRAINT pipeline_run_editor_uuid_fkey FOREIGN KEY (editor_uuid) REFERENCES "user" (uuid)
);

CREATE INDEX idx_pipeline_run_pipeline_uuid ON pipeline_run (pipeline_uuid);
CREATE INDEX idx_pipeline_run_started ON pipeline_run (started DESC);
CREATE INDEX idx_pipeline_run_status ON pipeline_run (status);

COMMENT ON TABLE pipeline_run IS 'History of pipeline executions';
COMMENT ON COLUMN pipeline_run.pipeline_uuid IS 'Reference to the pipeline definition';
COMMENT ON COLUMN pipeline_run.pipeline_version IS 'Version of the pipeline definition at time of run';
COMMENT ON COLUMN pipeline_run.started IS 'When the pipeline run started';
COMMENT ON COLUMN pipeline_run.finished IS 'When the pipeline run completed (null if still running)';
COMMENT ON COLUMN pipeline_run.status IS 'Current status: PENDING, RUNNING, SUCCESS, FAILED, PARTIAL, CANCELLED';
COMMENT ON COLUMN pipeline_run.media_count IS 'Total number of media items processed';
COMMENT ON COLUMN pipeline_run.success_count IS 'Number of media items processed successfully';
COMMENT ON COLUMN pipeline_run.failure_count IS 'Number of media items that failed';
COMMENT ON COLUMN pipeline_run.skipped_count IS 'Number of media items skipped';
COMMENT ON COLUMN pipeline_run.dry_run IS 'Whether this was a dry run (no side effects)';
COMMENT ON COLUMN pipeline_run.error_message IS 'Error message if the run failed';
COMMENT ON COLUMN pipeline_run.duration_ms IS 'Total duration of the run in milliseconds';
COMMENT ON COLUMN pipeline_run.meta IS 'Additional metadata (node stats, etc.)';