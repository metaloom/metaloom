-- Add the asset_node_result processing ledger.
--
-- This is the node-agnostic layer of the result model. "Has this node already processed
-- this asset?" is currently answered by AbstractMediaNode fetching the asset and probing
-- a field it happens to know about - md5 != null, a facedetect flag in an xattr. That
-- does not generalise: a node whose result is a JSON blob, or whose result is
-- legitimately empty, has nothing to probe. Nor could anyone answer "which assets did
-- the old face model touch?" after a model upgrade.
--
-- NOT the same as pipeline_node_task:
--   pipeline_node_task  is per RUN ITEM. Execution state. Pruned with the run.
--                       Keyed by (item_uuid, node_id).
--   asset_node_result   is per ASSET. Catalog state. Outlives every run.
--                       Keyed by (asset_uuid, node_kind, node_id).
-- Both may exist for the same execution; run_uuid/task_uuid are the join.

CREATE TABLE "asset_node_result" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar NOT NULL DEFAULT '',
    "producer_version" varchar NOT NULL DEFAULT '',

    "state"            varchar NOT NULL,
    "origin"           varchar,
    "reason"           varchar,

    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "started"          timestamp WITHOUT TIME ZONE,
    "finished"         timestamp WITHOUT TIME ZONE,
    "duration_ms"      bigint,

    "result_ref"       jsonb,
    "meta"             jsonb,

    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "asset_node_result_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_node_result_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_node_result_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_node_result_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_node_result_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_node_result_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_node_result_unique" UNIQUE ("asset_uuid", "node_kind", "node_id"),
    CONSTRAINT "asset_node_result_state_check" CHECK ("state" IN ('SUCCESS', 'SKIPPED', 'FAILED')),
    CONSTRAINT "asset_node_result_origin_check" CHECK ("origin" IS NULL OR "origin" IN ('COMPUTED', 'LOCAL', 'REMOTE'))
);

CREATE INDEX "idx_asset_node_result_asset_uuid" ON "asset_node_result" ("asset_uuid");
-- The invalidation sweep: everything facedetect produced before the current version.
CREATE INDEX "idx_asset_node_result_producer" ON "asset_node_result" ("node_kind", "producer_version");
CREATE INDEX "idx_asset_node_result_run_uuid" ON "asset_node_result" ("run_uuid");

COMMENT ON TABLE "asset_node_result" IS 'Per-asset processing ledger: has node X at version V processed asset A, and what happened - regardless of which table the payload landed in. Permanent catalog state, unlike pipeline_node_task which is per run item and is pruned with the run.';
COMMENT ON COLUMN "asset_node_result"."node_kind" IS 'Node kind, matching pipeline_node_task.node_kind';
COMMENT ON COLUMN "asset_node_result"."node_id" IS 'Graph-local node id; empty string when the node ran outside a pipeline';
COMMENT ON COLUMN "asset_node_result"."producer_version" IS 'Model/algorithm version. WHERE node_kind = ? AND producer_version <> ? is the invalidation query.';
COMMENT ON COLUMN "asset_node_result"."state" IS 'SUCCESS, SKIPPED or FAILED - mirrors ResultState in the Cortex node API';
COMMENT ON COLUMN "asset_node_result"."origin" IS 'COMPUTED, LOCAL or REMOTE - mirrors ResultOrigin in the Cortex node API';
COMMENT ON COLUMN "asset_node_result"."reason" IS 'Skip reason or failure detail';
COMMENT ON COLUMN "asset_node_result"."result_ref" IS 'Advisory pointer to the rows written, e.g. {"table":"asset_transcript_comp","uuids":[...]}. NOT a foreign key: a node may write several tables, and those rows carry their own task_uuid back-reference. Do not build integrity on it.';
COMMENT ON COLUMN "asset_node_result"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';
