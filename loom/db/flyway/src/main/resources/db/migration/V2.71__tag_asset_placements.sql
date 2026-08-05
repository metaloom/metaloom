-- Tag placements: a tag may be attached to an asset more than once, and every attachment records who
-- put it there.
--
-- Before this migration "tag_asset" was PRIMARY KEY ("tag_uuid", "asset_uuid"), which allowed exactly
-- one placement per (tag, asset) and thereby defeated the time_*/area* columns sitting in the same
-- table: tagging two faces in one photo, or one person at two timecodes of a video, was impossible -
-- which is precisely the output face detection and clustering are meant to produce. Nothing on the row
-- said whether a person or a pipeline attached it either, so the UI could not tell machine tags from
-- curated ones and a node could not prove a tag was its own before withdrawing it.
--
-- Requires PostgreSQL 15+ for UNIQUE NULLS NOT DISTINCT (test environment runs 16.3, the chart 17).

-- --- Identity -------------------------------------------------------------------------------------

ALTER TABLE "tag_asset" ADD COLUMN "uuid" uuid NOT NULL DEFAULT uuid_generate_v4();
ALTER TABLE "tag_asset" DROP CONSTRAINT "tag_asset_pkey";
ALTER TABLE "tag_asset" ADD CONSTRAINT "tag_asset_pkey" PRIMARY KEY ("uuid");

-- One placement per (tag, asset, region). An asset-level tag carries NULL in every region column, and
-- NULLs compare as distinct by default - which would let the same asset-level tag be attached again and
-- again. NULLS NOT DISTINCT makes "no region" a single value, so re-tagging stays the no-op it was while
-- two different regions become two rows.
--
-- areaWidth/areaHeight are deliberately outside the key: they size the box that areaStartX/areaStartY
-- place, so a corrected box is an update of the same placement rather than a second one.
ALTER TABLE "tag_asset" ADD CONSTRAINT "tag_asset_placement_key"
  UNIQUE NULLS NOT DISTINCT ("tag_uuid", "asset_uuid", "time_from", "time_to", "areaStartX", "areaStartY");

-- --- Provenance -----------------------------------------------------------------------------------

-- Mirrors "detection" (V2.43): node_kind names the writer, 'manual' meaning a person. It defaults to
-- 'manual' on purpose - an insert that forgets to say who wrote it is treated as human, because a
-- machine row mislabelled as human is merely not filtered out, while a human row mislabelled as machine
-- could be deleted by a node reconciling its own work.
ALTER TABLE "tag_asset" ADD COLUMN "node_kind" varchar NOT NULL DEFAULT 'manual';
ALTER TABLE "tag_asset" ADD COLUMN "node_id" varchar;
ALTER TABLE "tag_asset" ADD COLUMN "producer_version" varchar NOT NULL DEFAULT '';
ALTER TABLE "tag_asset" ADD COLUMN "confidence" real;
ALTER TABLE "tag_asset" ADD COLUMN "created" timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE "tag_asset" ADD COLUMN "creator_uuid" uuid;
ALTER TABLE "tag_asset" ADD CONSTRAINT "tag_asset_creator_uuid_fkey"
  FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");

-- --- Indexes --------------------------------------------------------------------------------------

-- The dropped primary key indexed tag_uuid first, and the new placement key does the same, so the
-- asset-side lookup ("which tags does this asset carry", the query behind every asset response) has
-- never had an index of its own.
CREATE INDEX "idx_tag_asset_asset" ON "tag_asset" ("asset_uuid");

-- Reconciliation asks "which placements on this asset did node X write".
CREATE INDEX "idx_tag_asset_node" ON "tag_asset" ("asset_uuid", "node_id");

-- --- Documentation --------------------------------------------------------------------------------

COMMENT ON TABLE "tag_asset" IS 'Placements of a tag on an asset. One row per (tag, asset, region); an asset-level tag has NULL in every region column. Rows written before V2.71 are labelled node_kind = manual because nothing recorded their author.';
COMMENT ON COLUMN "tag_asset"."uuid" IS 'Identity of the placement, which is what a caller removes when it wants one region rather than every occurrence of the tag';
COMMENT ON COLUMN "tag_asset"."node_kind" IS 'Which node kind attached the tag; the literal "manual" for a person';
COMMENT ON COLUMN "tag_asset"."node_id" IS 'Pipeline node id of the writer, so two instances of one node kind stay distinguishable. NULL for a person';
COMMENT ON COLUMN "tag_asset"."producer_version" IS 'Version of the answer the writer stands behind; changes when the meaning of the tag changes';
COMMENT ON COLUMN "tag_asset"."confidence" IS 'How sure the writer was, 0.0 - 1.0. NULL when the question does not apply, which is the normal case for a person';
COMMENT ON COLUMN "tag_asset"."creator_uuid" IS 'The principal that made the call, person or worker token. Authorship is node_kind; this is accountability';
