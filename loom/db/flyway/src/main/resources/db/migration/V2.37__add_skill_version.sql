-- Add skill version permissions to the loom_permission enum
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_SKILL_VERSION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'RESTORE_SKILL_VERSION';

-- Create skill_version table (immutable snapshots of the versioned skill body)
CREATE TABLE "skill_version" (
    "uuid"           uuid NOT NULL DEFAULT uuid_generate_v4(),
    "skill_uuid"     uuid NOT NULL,
    "version_number" integer NOT NULL,
    "description"    varchar NOT NULL,
    "content"        text NOT NULL,
    "meta"           jsonb,
    "created"        timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"   uuid NOT NULL,
    "edited"         timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"    uuid NOT NULL,
    CONSTRAINT "skill_version_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "skill_version_skill_uuid_fkey" FOREIGN KEY ("skill_uuid") REFERENCES "skill" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "skill_version_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "skill_version_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "skill_version_unique_version" UNIQUE ("skill_uuid", "version_number")
);

CREATE INDEX "idx_skill_version_skill_uuid" ON "skill_version" ("skill_uuid");

COMMENT ON TABLE "skill_version" IS 'Version history of the versioned skill body (description + content)';
COMMENT ON COLUMN "skill_version"."skill_uuid" IS 'Reference to the owning skill';
COMMENT ON COLUMN "skill_version"."version_number" IS 'Sequential version number (1, 2, 3, ...)';
COMMENT ON COLUMN "skill_version"."description" IS 'Skill description at this version';
COMMENT ON COLUMN "skill_version"."content" IS 'Skill markdown content at this version';
COMMENT ON COLUMN "skill_version"."meta" IS 'Additional metadata at this version';
COMMENT ON COLUMN "skill_version"."created" IS 'When this version was created';
COMMENT ON COLUMN "skill_version"."creator_uuid" IS 'User who created this version';

-- Add active_version_uuid pointer to the skill table
ALTER TABLE "skill" ADD COLUMN "active_version_uuid" uuid;

-- Migrate existing skill bodies into skill_version as v1 and capture the generated UUIDs
WITH v1_versions AS (
    INSERT INTO "skill_version" ("skill_uuid", "version_number", "description", "content", "meta", "created", "creator_uuid", "edited", "editor_uuid")
    SELECT
        "uuid",
        1,
        "description",
        "content",
        "meta",
        "created",
        "creator_uuid",
        "edited",
        "editor_uuid"
    FROM "skill"
    RETURNING "uuid", "skill_uuid"
)
UPDATE "skill" s
SET "active_version_uuid" = v."uuid"
FROM v1_versions v
WHERE s."uuid" = v."skill_uuid";

-- Add foreign key constraint for active_version_uuid
ALTER TABLE "skill"
ADD CONSTRAINT "skill_active_version_uuid_fkey"
FOREIGN KEY ("active_version_uuid") REFERENCES "skill_version" ("uuid");

-- Drop the now-versioned columns from the skill table (they live only on skill_version)
ALTER TABLE "skill" DROP COLUMN "description";
ALTER TABLE "skill" DROP COLUMN "content";

COMMENT ON COLUMN "skill"."active_version_uuid" IS 'Reference to the currently active skill_version record';
