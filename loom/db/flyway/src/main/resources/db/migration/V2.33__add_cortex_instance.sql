-- Persist registered Cortex (processor) instances and their node-kind restrictions.
--
-- Until now a registered worker lived only in the in-memory ProcessorRegistry and
-- died with the Loom process. Its node-kind restriction was whatever the worker
-- announced at WebSocket registration, with no way to remember or override it across
-- reconnects or restarts. This table gives every worker a durable identity keyed by
-- its stable node_id, plus an administrator-managed nodeWhitelist / nodeBlacklist.
--
-- The worker-announced restriction is the DEFAULT (seeded on first registration);
-- the row here is the OVERRIDE that survives reconnects and is edited from the UI.

-- Two-permission model: manage (write) and read (view), following the *_PIPELINE style.
ALTER TYPE "loom_permission" ADD VALUE 'MANAGE_CORTEX_INSTANCE';
ALTER TYPE "loom_permission" ADD VALUE 'READ_CORTEX_INSTANCE';

CREATE TABLE "cortex_instance" (
  "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "node_id"          varchar NOT NULL,
  "name"             varchar,
  "host"             varchar,
  "priority"         integer NOT NULL DEFAULT 0,
  "last_seen"        timestamp WITHOUT TIME ZONE,
  "state"            varchar,
  "first_registered" timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),

  "meta"             jsonb,
  -- A row is created by the machine that registers (no user), so the audit columns
  -- are nullable. The admin override path (UI) fills editor_uuid when it edits.
  "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "creator_uuid"     uuid,
  "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "editor_uuid"      uuid,

  CONSTRAINT "cortex_instance_pkey" PRIMARY KEY ("uuid"),
  CONSTRAINT "cortex_instance_node_id_key" UNIQUE ("node_id"),
  CONSTRAINT "cortex_instance_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
  CONSTRAINT "cortex_instance_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid")
);
COMMENT ON TABLE "cortex_instance" IS 'A registered Cortex/processor worker, keyed by its stable node_id';
COMMENT ON COLUMN "cortex_instance"."node_id" IS 'Stable worker identity used to reconcile the in-memory registry across reconnects';
COMMENT ON COLUMN "cortex_instance"."name" IS 'Human readable name of the worker';
COMMENT ON COLUMN "cortex_instance"."host" IS 'Host address of the worker (e.g. 10.0.1.10:9090)';
COMMENT ON COLUMN "cortex_instance"."priority" IS 'Selection priority (higher = preferred)';
COMMENT ON COLUMN "cortex_instance"."last_seen" IS 'Timestamp of the last registration/heartbeat';
COMMENT ON COLUMN "cortex_instance"."state" IS 'Last known processor state (e.g. ONLINE)';
COMMENT ON COLUMN "cortex_instance"."first_registered" IS 'When this worker first registered';

-- Restriction lists as a queryable/indexable child table rather than an opaque JSONB
-- blob, so a single node kind can be looked up across all workers.
CREATE TABLE "cortex_instance_node_kind" (
  "instance_uuid" uuid NOT NULL,
  "node_kind"     varchar NOT NULL,
  "list"          varchar NOT NULL,
  CONSTRAINT "cortex_instance_node_kind_pkey" PRIMARY KEY ("instance_uuid", "node_kind", "list"),
  CONSTRAINT "cortex_instance_node_kind_list_check" CHECK ("list" IN ('WHITELIST', 'BLACKLIST')),
  CONSTRAINT "cortex_instance_node_kind_instance_fkey" FOREIGN KEY ("instance_uuid") REFERENCES "cortex_instance" ("uuid") ON DELETE CASCADE
);
COMMENT ON TABLE "cortex_instance_node_kind" IS 'Per-worker node-kind whitelist/blacklist entries';
COMMENT ON COLUMN "cortex_instance_node_kind"."node_kind" IS 'A pipeline node kind this worker is allowed (WHITELIST) or forbidden (BLACKLIST) to run';
COMMENT ON COLUMN "cortex_instance_node_kind"."list" IS 'Which list the entry belongs to: WHITELIST or BLACKLIST';

CREATE INDEX "idx_cortex_instance_node_kind_kind" ON "cortex_instance_node_kind" ("node_kind");
