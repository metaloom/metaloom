-- Skill permission entries
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'CREATE_SKILL';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'READ_SKILL';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'DELETE_SKILL';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'UPDATE_SKILL';

CREATE TABLE "skill" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar NOT NULL,
  "description" varchar NOT NULL,
  "content" text NOT NULL,
  "enabled" boolean NOT NULL DEFAULT true,
  "published" boolean NOT NULL DEFAULT false,
  "origin_skill_uuid" uuid,
  "meta" jsonb,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid"),
  UNIQUE ("creator_uuid", "name")
);
ALTER TABLE "skill" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "skill" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "skill" ADD FOREIGN KEY ("origin_skill_uuid") REFERENCES "skill" ("uuid") ON DELETE SET NULL;

COMMENT ON TABLE "skill" IS 'Stores user-owned agent skills (SKILL.md-style instruction packages for the chat agent)';
COMMENT ON COLUMN "skill"."name" IS 'Machine-friendly skill name, unique per owner';
COMMENT ON COLUMN "skill"."description" IS 'Short description which gets injected into the agent system prompt';
COMMENT ON COLUMN "skill"."content" IS 'Full markdown instructions of the skill';
COMMENT ON COLUMN "skill"."enabled" IS 'Owner-level default on/off toggle';
COMMENT ON COLUMN "skill"."published" IS 'Whether the skill is visible in the shared skill library';
COMMENT ON COLUMN "skill"."origin_skill_uuid" IS 'Provenance reference to the published skill this one was installed from';
COMMENT ON COLUMN "skill"."created" IS 'Creation timestamp';
COMMENT ON COLUMN "skill"."edited" IS 'Last edit timestamp';
