-- Add pipeline version permissions to the loom_permission enum
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'CREATE_PIPELINE_VERSION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_PIPELINE_VERSION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'RESTORE_PIPELINE_VERSION';

-- Create pipeline_version table
CREATE TABLE pipeline_version (
    uuid           UUID NOT NULL DEFAULT uuid_generate_v4(),
    pipeline_uuid  UUID NOT NULL,
    version_number INTEGER NOT NULL,
    name           VARCHAR NOT NULL,
    description    VARCHAR,
    definition     JSONB NOT NULL DEFAULT '{}'::jsonb,
    enabled        BOOLEAN NOT NULL DEFAULT true,
    priority       INTEGER NOT NULL DEFAULT 0,
    dry_run        BOOLEAN NOT NULL DEFAULT false,
    meta           JSONB,
    created        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    creator_uuid   UUID NOT NULL,
    CONSTRAINT pipeline_version_pkey PRIMARY KEY (uuid),
    CONSTRAINT pipeline_version_pipeline_uuid_fkey FOREIGN KEY (pipeline_uuid) REFERENCES pipeline (uuid) ON DELETE CASCADE,
    CONSTRAINT pipeline_version_creator_uuid_fkey FOREIGN KEY (creator_uuid) REFERENCES "user" (uuid),
    CONSTRAINT pipeline_version_unique_version UNIQUE (pipeline_uuid, version_number)
);

CREATE INDEX idx_pipeline_version_pipeline_uuid ON pipeline_version (pipeline_uuid);
CREATE INDEX idx_pipeline_version_version_number ON pipeline_version (pipeline_uuid, version_number);

COMMENT ON TABLE pipeline_version IS 'Version history of pipeline definitions';
COMMENT ON COLUMN pipeline_version.pipeline_uuid IS 'Reference to the pipeline';
COMMENT ON COLUMN pipeline_version.version_number IS 'Sequential version number (1, 2, 3, ...)';
COMMENT ON COLUMN pipeline_version.name IS 'Pipeline name at this version';
COMMENT ON COLUMN pipeline_version.description IS 'Pipeline description at this version';
COMMENT ON COLUMN pipeline_version.definition IS 'Pipeline definition JSON at this version';
COMMENT ON COLUMN pipeline_version.enabled IS 'Pipeline enabled state at this version';
COMMENT ON COLUMN pipeline_version.priority IS 'Pipeline priority at this version';
COMMENT ON COLUMN pipeline_version.dry_run IS 'Pipeline dry_run state at this version';
COMMENT ON COLUMN pipeline_version.meta IS 'Additional metadata at this version';
COMMENT ON COLUMN pipeline_version.created IS 'When this version was created';
COMMENT ON COLUMN pipeline_version.creator_uuid IS 'User who created this version';

-- Add latest_version_uuid column to pipeline table
ALTER TABLE pipeline ADD COLUMN latest_version_uuid UUID;

-- Migrate existing pipeline data to pipeline_version as v1
-- We use a CTE to insert v1 versions and capture the generated UUIDs
WITH v1_versions AS (
    INSERT INTO pipeline_version (pipeline_uuid, version_number, name, description, definition, enabled, priority, dry_run, meta, created, creator_uuid)
    SELECT 
        uuid,
        1,
        name,
        description,
        definition,
        enabled,
        priority,
        dry_run,
        meta,
        created,
        creator_uuid
    FROM pipeline
    RETURNING uuid, pipeline_uuid
)
UPDATE pipeline p
SET latest_version_uuid = v.uuid
FROM v1_versions v
WHERE p.uuid = v.pipeline_uuid;

-- Add foreign key constraint for latest_version_uuid
ALTER TABLE pipeline 
ADD CONSTRAINT pipeline_latest_version_uuid_fkey 
FOREIGN KEY (latest_version_uuid) REFERENCES pipeline_version (uuid);

-- Drop duplicated columns from pipeline table
ALTER TABLE pipeline DROP COLUMN name;
ALTER TABLE pipeline DROP COLUMN description;
ALTER TABLE pipeline DROP COLUMN definition;
ALTER TABLE pipeline DROP COLUMN enabled;
ALTER TABLE pipeline DROP COLUMN priority;
ALTER TABLE pipeline DROP COLUMN dry_run;

COMMENT ON COLUMN pipeline.latest_version_uuid IS 'Reference to the latest pipeline_version record';