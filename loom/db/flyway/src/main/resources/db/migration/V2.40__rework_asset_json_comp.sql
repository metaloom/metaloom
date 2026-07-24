-- Harden asset_json_comp as the generic, node-agnostic result sink.
--
-- This is the destination for LLMNode, CaptioningNode, FacedescriptionNode and every
-- node kind added in future. It previously allowed a NULL schema_type, had no unique
-- key and no discriminator, so an LLM node configured with three prompts produced three
-- rows that could not be told apart - and a retry produced six.
--
-- DESTRUCTIVE rewrite - see V2.38 for the shared component contract.

DROP TABLE IF EXISTS "asset_json_comp" CASCADE;

CREATE TABLE "asset_json_comp" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "confidence"       real,

    "schema_type"      varchar NOT NULL,
    "variant"          varchar NOT NULL DEFAULT '',
    "data"             jsonb NOT NULL DEFAULT '{}',

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "asset_json_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_json_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_json_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_json_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_json_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_json_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_json_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "schema_type", "variant")
);

CREATE INDEX "idx_asset_json_comp_asset_uuid" ON "asset_json_comp" ("asset_uuid");
CREATE INDEX "idx_asset_json_comp_schema_type" ON "asset_json_comp" ("schema_type");
CREATE INDEX "idx_asset_json_comp_data" ON "asset_json_comp" USING GIN ("data" jsonb_path_ops);

COMMENT ON TABLE "asset_json_comp" IS 'Generic sink for node results with no query requirement. A node kind starts here and graduates to a typed component table when a query must filter on a field inside data, when the UI renders it as a first-class object, or when it needs a foreign key.';
COMMENT ON COLUMN "asset_json_comp"."node_kind" IS 'Producing node kind, e.g. llm, captioning, facedescription';
COMMENT ON COLUMN "asset_json_comp"."schema_type" IS 'Shape label for the payload, e.g. yolo-detection, caption, llm-answer';
COMMENT ON COLUMN "asset_json_comp"."variant" IS 'Sub-division within the kind: prompt id, model tag, whatever makes two results of the same node distinct';
COMMENT ON COLUMN "asset_json_comp"."data" IS 'The payload. NOT NULL: "the node ran and produced nothing" is expressed by asset_node_result, not by a NULL payload.';
COMMENT ON COLUMN "asset_json_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';
