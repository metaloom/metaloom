-- Add asset_segment_comp.
--
-- SceneDetectionNode produces a list of time ranges and had nowhere to put it.
-- Time-ranged results are a whole category, not a one-off: scene boundaries, silence
-- detection, shot changes and LLM-generated chapters all share the same shape, and the
-- UI timeline needs them as rows to render and seek. Forcing them into asset_json_comp
-- would mean the timeline cannot query "segments overlapping 00:12:30" without parsing
-- every blob.
--
-- See V2.38 for the shared component contract.

CREATE TABLE "asset_segment_comp" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "confidence"       real,

    "segment_type"     varchar NOT NULL,
    "seq"              int NOT NULL,
    "time_from"        bigint NOT NULL,
    "time_to"          bigint NOT NULL,
    "title"            varchar,
    "score"            real,

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "asset_segment_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_segment_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_segment_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_segment_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_segment_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_segment_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_segment_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "segment_type", "seq"),
    CONSTRAINT "asset_segment_comp_range_check" CHECK ("time_to" >= "time_from"),
    -- CHECKed varchar rather than a PG enum: node authors will add kinds, and
    -- ALTER TYPE ... ADD VALUE cannot run inside a Flyway transaction. Extend this list
    -- when a new segment kind appears.
    CONSTRAINT "asset_segment_comp_type_check" CHECK ("segment_type" IN ('SCENE', 'SILENCE', 'SHOT', 'CHAPTER'))
);

CREATE INDEX "idx_asset_segment_comp_asset_uuid" ON "asset_segment_comp" ("asset_uuid");
-- The timeline query: which segments of this kind overlap a given point in time.
CREATE INDEX "idx_asset_segment_comp_timeline" ON "asset_segment_comp" ("asset_uuid", "segment_type", "time_from");

COMMENT ON TABLE "asset_segment_comp" IS 'Time-ranged results: scenes, silence, shot changes, chapters. Replace-in-place happens at the SET level: a re-run writes seq 0..N-1 and must delete rows with seq >= N for that (asset_uuid, node_kind, segment_type). That is the one write path which is not a single upsert statement.';
COMMENT ON COLUMN "asset_segment_comp"."segment_type" IS 'SCENE, SILENCE, SHOT or CHAPTER - see the type check constraint';
COMMENT ON COLUMN "asset_segment_comp"."seq" IS 'Ordinal within (asset_uuid, node_kind, segment_type), starting at 0';
COMMENT ON COLUMN "asset_segment_comp"."time_from" IS 'Segment start in milliseconds';
COMMENT ON COLUMN "asset_segment_comp"."time_to" IS 'Segment end in milliseconds';
COMMENT ON COLUMN "asset_segment_comp"."title" IS 'Human readable label, e.g. a chapter title produced by the LLM node';
COMMENT ON COLUMN "asset_segment_comp"."score" IS 'Detector score for this boundary, distinct from the extraction confidence';
COMMENT ON COLUMN "asset_segment_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';
