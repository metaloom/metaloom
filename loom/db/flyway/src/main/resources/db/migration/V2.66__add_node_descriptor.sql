-- Persist the node contracts Cortex workers announce.
--
-- Until now the contract for a node lived only in Loom's own jar, compiled in and
-- discovered through ServiceLoader at boot. A node dropped onto a worker's classpath
-- was therefore runnable but *unauthorable*: dispatch knew its name from the REGISTER
-- whitelist, but the editor could not place it and the graph parser rejected it as
-- unknown. A worker now announces its contracts over NODE_REGISTRATION, and this is
-- where Loom keeps them.
--
-- The load-bearing idea is that spec knowledge is DURABLE while worker presence is LIVE.
-- A node whose last worker went offline keeps validating, keeps saving and keeps opening
-- in the editor; it simply cannot run, which unsupportedNodeKinds already reports as a
-- 503. Nothing here is deleted when a worker disconnects - that would turn a 30 second
-- rolling restart into "your saved pipeline no longer validates". Rows are removed only
-- by an explicit admin action.

-- Keyed by a row uuid with node_id UNIQUE, exactly like cortex_instance, rather than by
-- node_id directly: CRUDDao and AbstractJooqDao address rows by uuid, and a table without
-- one has to bypass the whole DAO framework to be readable.
CREATE TABLE "node_descriptor" (
  "uuid"           uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "node_id"        varchar NOT NULL,
  "version"        varchar,
  "descriptor"     jsonb   NOT NULL,
  "body_hash"      varchar NOT NULL,
  "source"         varchar NOT NULL,
  "status"         varchar NOT NULL,
  "first_seen"     timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "last_announced" timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),

  "meta"           jsonb,
  -- Written by a machine that announces (no user), so the audit columns are nullable.
  -- An admin deleting a row fills editor_uuid.
  "created"        timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "creator_uuid"   uuid,
  "edited"         timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "editor_uuid"    uuid,

  CONSTRAINT "node_descriptor_pkey" PRIMARY KEY ("uuid"),
  CONSTRAINT "node_descriptor_node_id_key" UNIQUE ("node_id"),
  CONSTRAINT "node_descriptor_source_check" CHECK ("source" IN ('ANNOUNCED')),
  CONSTRAINT "node_descriptor_status_check" CHECK ("status" IN ('ACTIVE', 'CONFLICTED')),
  CONSTRAINT "node_descriptor_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
  CONSTRAINT "node_descriptor_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid")
);
COMMENT ON TABLE "node_descriptor" IS 'A node contract announced by a Cortex worker, kept so the node stays authorable while no worker is online';
COMMENT ON COLUMN "node_descriptor"."node_id" IS 'The node TYPE id (whisper, acme-nsfw). Not the graph-instance id of asset_node_result.node_id, and not the worker id of cortex_instance.node_id - three different things wear this name';
COMMENT ON COLUMN "node_descriptor"."version" IS 'The active contract version: the LOWEST announced by any worker offering this node';
COMMENT ON COLUMN "node_descriptor"."descriptor" IS 'The full announced NodeDescriptor JSON, so rehydrating at boot needs no worker';
COMMENT ON COLUMN "node_descriptor"."body_hash" IS 'SHA-256 of the canonical, key-sorted contract body, excluding version and the deprecated kind alias';
COMMENT ON COLUMN "node_descriptor"."source" IS 'Always ANNOUNCED. BUILTIN contracts are never persisted - they are recomputed from the classpath at every boot, and a stored copy would outlive a Loom downgrade';
COMMENT ON COLUMN "node_descriptor"."status" IS 'ACTIVE, or CONFLICTED when workers announce the same version with different bodies';
COMMENT ON COLUMN "node_descriptor"."last_announced" IS 'When this contract last arrived over the socket. NOT liveness: a worker announces once and then stays connected for days, so availability is read from cortex_instance.last_seen through node_descriptor_instance';

-- Each worker's own claim, as a child table rather than a JSONB blob, so one node id is
-- queryable across the whole fleet. This is what makes "which contract is active" a
-- query rather than a cached decision that can rot: when a worker unlinks, the answer is
-- recomputed from what remains.
CREATE TABLE "node_descriptor_instance" (
  "node_id"        varchar NOT NULL,
  "instance_uuid"  uuid    NOT NULL,
  "version"        varchar,
  "body_hash"      varchar NOT NULL,
  "last_announced" timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),

  CONSTRAINT "node_descriptor_instance_pkey" PRIMARY KEY ("node_id", "instance_uuid"),
  CONSTRAINT "node_descriptor_instance_cortex_fkey" FOREIGN KEY ("instance_uuid") REFERENCES "cortex_instance" ("uuid") ON DELETE CASCADE
);
COMMENT ON TABLE "node_descriptor_instance" IS 'Which worker claims which node contract, and at which version';
COMMENT ON COLUMN "node_descriptor_instance"."node_id" IS 'The node TYPE id. Deliberately not a foreign key to node_descriptor: a worker also claims built-in node ids, whose contracts are never persisted';

CREATE INDEX "idx_node_descriptor_instance_node_id" ON "node_descriptor_instance" ("node_id");
