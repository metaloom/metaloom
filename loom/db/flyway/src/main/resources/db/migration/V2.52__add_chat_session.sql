-- Chat session permission entries
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'CREATE_CHAT_SESSION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'READ_CHAT_SESSION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'UPDATE_CHAT_SESSION';
ALTER TYPE "loom_permission" ADD VALUE IF NOT EXISTS 'DELETE_CHAT_SESSION';

-- A durable, publishable record behind one chat: its name/description (AI-generated on first use),
-- tags, publish flag, and a snapshot of the coding sandbox filesystem (/session).
CREATE TABLE "chat_session" (
  "uuid"         uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "chat_uuid"    uuid,
  "name"         varchar NOT NULL,
  "description"  varchar,
  "tags"         text[] NOT NULL DEFAULT '{}',
  "published"    boolean NOT NULL DEFAULT false,

  -- Filesystem snapshot of /session (stored via the asset pool; NULL until first snapshot).
  "pool_uuid"    uuid,
  "blob_path"    varchar,
  "fs_size"      bigint,
  "fs_sha256"    varchar,

  "meta"         jsonb,

  "created"      timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited"       timestamp NOT NULL DEFAULT (now()),
  "editor_uuid"  uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
ALTER TABLE "chat_session" ADD FOREIGN KEY ("chat_uuid") REFERENCES "chat" ("uuid") ON DELETE SET NULL;
ALTER TABLE "chat_session" ADD FOREIGN KEY ("pool_uuid") REFERENCES "asset_pool" ("uuid") ON DELETE SET NULL;
ALTER TABLE "chat_session" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "chat_session" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

CREATE INDEX "idx_chat_session_published" ON "chat_session" ("published");
CREATE INDEX "idx_chat_session_creator" ON "chat_session" ("creator_uuid");

COMMENT ON TABLE "chat_session" IS 'Publishable record behind one chat: transcript-derived name/description, tags, publish flag and a /session filesystem snapshot';
COMMENT ON COLUMN "chat_session"."chat_uuid" IS 'The owning chat (set null if the chat is deleted, so a published session survives)';
COMMENT ON COLUMN "chat_session"."name" IS 'Session name (AI-generated on first use, user-editable)';
COMMENT ON COLUMN "chat_session"."description" IS 'Session description (AI-generated on first use, user-editable)';
COMMENT ON COLUMN "chat_session"."published" IS 'Whether the session is visible in the shared session library';
COMMENT ON COLUMN "chat_session"."blob_path" IS 'Path of the /session tarball within the referenced asset pool';

-- Active skill versions pinned by the session, so a shared session is reproducible.
CREATE TABLE "chat_session_skill" (
  "session_uuid"  uuid NOT NULL,
  "skill_uuid"    uuid NOT NULL,
  "skill_version" integer NOT NULL,
  PRIMARY KEY ("session_uuid", "skill_uuid")
);
ALTER TABLE "chat_session_skill" ADD FOREIGN KEY ("session_uuid") REFERENCES "chat_session" ("uuid") ON DELETE CASCADE;
ALTER TABLE "chat_session_skill" ADD FOREIGN KEY ("skill_uuid") REFERENCES "skill" ("uuid") ON DELETE CASCADE;

COMMENT ON TABLE "chat_session_skill" IS 'Skill versions active for a chat session (pins skill_version.version_number)';

-- Context composition: which OTHER (published) sessions feed this session, and which parts.
-- Deleting a session cascades only its own ref rows (as referencing session, and as source) — it
-- never deletes the referenced session or its skills.
CREATE TABLE "chat_session_context_ref" (
  "session_uuid"         uuid NOT NULL,
  "source_session_uuid"  uuid NOT NULL,
  "include_chat_history" boolean NOT NULL DEFAULT false,
  "include_skills"       boolean NOT NULL DEFAULT false,
  "include_filesystem"   boolean NOT NULL DEFAULT false,
  "ordinal"              integer NOT NULL DEFAULT 0,
  PRIMARY KEY ("session_uuid", "source_session_uuid")
);
ALTER TABLE "chat_session_context_ref" ADD FOREIGN KEY ("session_uuid") REFERENCES "chat_session" ("uuid") ON DELETE CASCADE;
ALTER TABLE "chat_session_context_ref" ADD FOREIGN KEY ("source_session_uuid") REFERENCES "chat_session" ("uuid") ON DELETE CASCADE;

CREATE INDEX "idx_chat_session_context_ref_source" ON "chat_session_context_ref" ("source_session_uuid");

COMMENT ON TABLE "chat_session_context_ref" IS 'Live references from a session to other published sessions, with per-part (chat history / skills / filesystem) toggles';
