-- Agent memory permission entries
-- NOTE: values added via ALTER TYPE ... ADD VALUE must not be referenced in this same migration.
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'CREATE_MEMORY';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_MEMORY';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'UPDATE_MEMORY';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'DELETE_MEMORY';

-- Scope of a memory entry. USER is the private default; GROUP and SPACE are shared scopes.
CREATE TYPE "memory_scope" AS ENUM ('USER', 'GROUP', 'SPACE');

-- The agent memory bank: scoped markdown notes addressed by a path-like id.
CREATE TABLE "memory_entry" (
  "uuid"          uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "scope"         memory_scope NOT NULL,
  "scope_uuid"    uuid NOT NULL,
  "memory_id"     varchar NOT NULL,
  "title"         varchar,
  "body"          text NOT NULL,
  "size"          integer NOT NULL DEFAULT 0,
  "sha256"        varchar NOT NULL,
  "version"       integer NOT NULL DEFAULT 1,
  "session_name"  varchar,
  "chat_uuid"     uuid,
  "meta"          jsonb,

  "created"       timestamp NOT NULL DEFAULT (now()),
  "creator_uuid"  uuid NOT NULL,
  "edited"        timestamp NOT NULL DEFAULT (now()),
  "editor_uuid"   uuid NOT NULL,

  PRIMARY KEY ("uuid"),
  UNIQUE ("scope", "scope_uuid", "memory_id")
);
ALTER TABLE "memory_entry" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "memory_entry" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "memory_entry" ADD FOREIGN KEY ("chat_uuid") REFERENCES "chat" ("uuid") ON DELETE SET NULL;

CREATE INDEX "idx_memory_entry_scope" ON "memory_entry" ("scope", "scope_uuid");

COMMENT ON TABLE "memory_entry" IS 'Agent memory bank: scoped markdown notes addressed by a path-like id';
COMMENT ON COLUMN "memory_entry"."scope_uuid" IS 'user.uuid, group.uuid or project.uuid depending on scope; intentionally no FK since it spans three tables';
COMMENT ON COLUMN "memory_entry"."memory_id" IS 'Path-like id relative to the scope, e.g. projects/loom-db.md';
COMMENT ON COLUMN "memory_entry"."body" IS 'Markdown body without frontmatter; the header is rendered from the columns';
COMMENT ON COLUMN "memory_entry"."size" IS 'Byte length of the body, kept denormalized so scope quotas are a single SUM()';
COMMENT ON COLUMN "memory_entry"."sha256" IS 'Digest of the rendered file, used to skip unchanged files when syncing into a session container';
COMMENT ON COLUMN "memory_entry"."version" IS 'Bumped on every update; the anchor for a future memory_entry_version table';
COMMENT ON COLUMN "memory_entry"."session_name" IS 'Name of the chat session that last wrote this entry (denormalized for the rendered header)';

-- The space (project) a chat originates from; scopes space-level agent memory.
ALTER TABLE "chat" ADD COLUMN "space_uuid" uuid;
ALTER TABLE "chat" ADD FOREIGN KEY ("space_uuid") REFERENCES "project" ("uuid") ON DELETE SET NULL;
CREATE INDEX "idx_chat_space" ON "chat" ("space_uuid");
COMMENT ON COLUMN "chat"."space_uuid" IS 'Space (project) the chat originates from; scopes space-level agent memory';
