-- Chat permission entries
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'CREATE_CHAT';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'READ_CHAT';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'DELETE_CHAT';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'UPDATE_CHAT';

CREATE TABLE "chat" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "title" varchar NOT NULL,
  "messages" jsonb NOT NULL DEFAULT '[]'::jsonb,
  "meta" jsonb,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
ALTER TABLE "chat" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "chat" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

COMMENT ON TABLE "chat" IS 'Stores LLM chat sessions with message history';
COMMENT ON COLUMN "chat"."title" IS 'Short title / summary of the chat session';
COMMENT ON COLUMN "chat"."messages" IS 'JSON array of chat messages (role, content, metadata, asset references)';
COMMENT ON COLUMN "chat"."created" IS 'Creation timestamp';
COMMENT ON COLUMN "chat"."edited" IS 'Last edit timestamp';
