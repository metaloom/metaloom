-- Memory deny rule permission entries
-- NOTE: values added via ALTER TYPE ... ADD VALUE must not be referenced in this same migration.
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'CREATE_MEMORY_DENY_RULE';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_MEMORY_DENY_RULE';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'UPDATE_MEMORY_DENY_RULE';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'DELETE_MEMORY_DENY_RULE';

-- The memory denylist: instance-wide, admin-curated patterns that must never be written into the
-- agent memory bank. Every put_memory body and title is matched against the enabled rules, and a hit
-- rejects the write with this row's message.
CREATE TABLE "memory_deny_rule" (
  "uuid"          uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "name"          varchar NOT NULL,
  "pattern"       varchar NOT NULL,
  "message"       varchar NOT NULL,
  "enabled"       boolean NOT NULL DEFAULT true,
  "meta"          jsonb,

  "created"       timestamp NOT NULL DEFAULT (now()),
  "creator_uuid"  uuid NOT NULL,
  "edited"        timestamp NOT NULL DEFAULT (now()),
  "editor_uuid"   uuid NOT NULL,

  PRIMARY KEY ("uuid"),
  UNIQUE ("name")
);
ALTER TABLE "memory_deny_rule" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "memory_deny_rule" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

CREATE INDEX "idx_memory_deny_rule_enabled" ON "memory_deny_rule" ("enabled");

COMMENT ON TABLE "memory_deny_rule" IS 'Instance-wide denylist of regular expressions which must never be stored in the agent memory bank';
COMMENT ON COLUMN "memory_deny_rule"."name" IS 'Human readable rule name, unique across the instance';
COMMENT ON COLUMN "memory_deny_rule"."pattern" IS 'Java regular expression matched against the note body and title; use alternation to cover several phrases in one rule';
COMMENT ON COLUMN "memory_deny_rule"."message" IS 'Message returned to the agent when this rule rejects a write; it must explain the problem without echoing the matched text';
COMMENT ON COLUMN "memory_deny_rule"."enabled" IS 'Disabled rules are kept for reference but never matched';
