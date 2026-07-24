-- Add asset_fingerprint_comp.
--
-- FingerprintNode computes a multi-sector video fingerprint and FingerprintDedupNode
-- consumes it, but there was nowhere to put it: not a hash column on asset, not an
-- embedding, not a component. FingerprintInfo in the REST model already declared a
-- fingerprintV1 field with no backing column, and dedup-by-fingerprint could not be a
-- database query at all - the whole point of computing it.
--
-- See V2.38 for the shared component contract.

CREATE TABLE "asset_fingerprint_comp" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "asset_uuid"       uuid NOT NULL,

    "node_kind"        varchar NOT NULL,
    "node_id"          varchar,
    "producer_version" varchar NOT NULL DEFAULT '',
    "run_uuid"         uuid,
    "task_uuid"        uuid,
    "confidence"       real,

    "algorithm"        varchar NOT NULL,
    "sector_index"     int NOT NULL DEFAULT 0,
    "time_from"        bigint,
    "time_to"          bigint,
    "fingerprint"      varchar NOT NULL,

    "meta"             jsonb,
    "created"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"     uuid,
    "edited"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"      uuid,

    CONSTRAINT "asset_fingerprint_comp_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "asset_fingerprint_comp_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "asset_fingerprint_comp_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_fingerprint_comp_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "asset_fingerprint_comp_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_fingerprint_comp_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "asset_fingerprint_comp_unique_key" UNIQUE ("asset_uuid", "node_kind", "algorithm", "sector_index"),
    CONSTRAINT "asset_fingerprint_comp_range_check" CHECK ("time_to" IS NULL OR "time_from" IS NULL OR "time_to" >= "time_from")
);

CREATE INDEX "idx_asset_fingerprint_comp_asset_uuid" ON "asset_fingerprint_comp" ("asset_uuid");
-- The dedup lookup: given a fingerprint, find every asset that shares it.
CREATE INDEX "idx_asset_fingerprint_comp_lookup" ON "asset_fingerprint_comp" ("algorithm", "fingerprint");

COMMENT ON TABLE "asset_fingerprint_comp" IS 'Perceptual fingerprints of an asset, one row per sector. Indexed by (algorithm, fingerprint) so dedup is an index scan rather than a table walk.';
COMMENT ON COLUMN "asset_fingerprint_comp"."algorithm" IS 'Fingerprint algorithm identifier, e.g. metaloom-multisector-v1';
COMMENT ON COLUMN "asset_fingerprint_comp"."sector_index" IS 'Which sector of a multi-sector fingerprint; 0 for whole-asset fingerprints';
COMMENT ON COLUMN "asset_fingerprint_comp"."time_from" IS 'Start of the window this sector covers, in milliseconds';
COMMENT ON COLUMN "asset_fingerprint_comp"."time_to" IS 'End of the window this sector covers, in milliseconds';
COMMENT ON COLUMN "asset_fingerprint_comp"."fingerprint" IS 'The fingerprint value, hex or base64 encoded. bytea is the better long-term type if these values grow.';
COMMENT ON COLUMN "asset_fingerprint_comp"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user';
