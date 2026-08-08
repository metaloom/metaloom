-- Make "cluster" a machine-writable, human-reviewable identity proposal.
--
-- The face workflow is detect -> embed -> cluster -> confirm a person
-- (spec/workflows/WORKFLOW_FACE.md). Detection and embedding both persist; clustering could not,
-- for a structural reason: cluster.creator_uuid was NOT NULL referencing "user". V2.47 made the
-- audit columns nullable on every other producer table and skipped this one, so a Cortex worker
-- physically could not insert a cluster. Nor was there any relation between "cluster" and
-- "person" - two competing models of "a person" coexisted with no FK, no join table and no code
-- path between them.
--
-- This migration mirrors the dedup review model (V2.61): a node proposes with status PENDING, a
-- human confirms, and the confirmation links the proposal to a person. The provenance columns are
-- the same component contract every other producer table carries (V2.38, V2.43), so a pack change
-- retires stale proposals through the standard sweep
-- (WHERE node_kind = ? AND producer_version <> ?).
--
-- ON DELETE semantics, stated once:
--   asset_uuid   CASCADE  - a per-asset cluster describes that asset and is meaningless without it.
--   person_uuid  SET NULL - deleting a person must not erase the review record. Same reasoning
--                           V2.61 applies to dedup_group.keep_asset_uuid.
--   run/task     SET NULL - provenance is advisory; losing a run must not delete results (V2.43).
--   creator/editor        - unchanged (no action): those are users, and users are not deleted
--                           casually. They merely become nullable.

CREATE TYPE "cluster_status" AS ENUM ('PENDING', 'CONFIRMED', 'REJECTED');

-- ---------------------------------------------------------------------------
-- 1. A Cortex worker is not a user.
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ALTER COLUMN "creator_uuid" DROP NOT NULL;
ALTER TABLE "cluster" ALTER COLUMN "editor_uuid" DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. Names. A machine proposal has none until a human supplies one, and V2.12's index made the
--    name unique across ALL cluster types at once - so two people called "Anna Meyer" could not
--    coexist, and a face cluster collided with an unrelated fingerprint cluster of the same name.
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ALTER COLUMN "name" DROP NOT NULL;

-- V2.12 wrote CREATE UNIQUE INDEX ON "cluster" ("name") with no name, so the index is called
-- whatever PostgreSQL chose. Locate it by shape rather than trusting a version-dependent
-- auto-generated name.
DO $$
DECLARE
    idx_name text;
BEGIN
    SELECT i.relname INTO idx_name
      FROM pg_index x
      JOIN pg_class i ON i.oid = x.indexrelid
      JOIN pg_class t ON t.oid = x.indrelid
      JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = x.indkey[0]
     WHERE t.relname = 'cluster'
       AND x.indisunique
       AND x.indnkeyatts = 1
       AND a.attname = 'name';
    IF idx_name IS NOT NULL THEN
        EXECUTE format('DROP INDEX %I', idx_name);
    END IF;
END $$;

-- Cannot fail on existing rows: the index just dropped made "name" globally unique, so no two
-- rows can share one, let alone share a (type, name) pair.
CREATE UNIQUE INDEX "cluster_type_name_key" ON "cluster" ("type", "name") WHERE "name" IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. Provenance: the component contract (V2.38, V2.43).
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ADD COLUMN "node_kind" varchar;
ALTER TABLE "cluster" ADD COLUMN "node_id" varchar;
ALTER TABLE "cluster" ADD COLUMN "producer_version" varchar NOT NULL DEFAULT '';
ALTER TABLE "cluster" ADD COLUMN "run_uuid" uuid;
ALTER TABLE "cluster" ADD COLUMN "task_uuid" uuid;

-- ---------------------------------------------------------------------------
-- 4./5. Scope and idempotency. asset_uuid is NULLABLE on purpose: a phase-2 library-wide cluster
--       lives in this same table, distinguished by node_kind, without a second migration.
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ADD COLUMN "asset_uuid" uuid;
ALTER TABLE "cluster" ADD COLUMN "cluster_index" int NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- 6. Review state.
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ADD COLUMN "status" "cluster_status" NOT NULL DEFAULT 'PENDING';

-- ---------------------------------------------------------------------------
-- 7. The missing link between "cluster" and "person".
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ADD COLUMN "person_uuid" uuid;

-- ---------------------------------------------------------------------------
-- 8. Ranking, and phase-2 matching without re-reading every member. model/dimensions travel with
--    the centroid because a centroid computed by another model is not comparable to it - the same
--    (type, model, dimensions) identity the embedding table and the VectorIndex SPI already use.
-- ---------------------------------------------------------------------------
ALTER TABLE "cluster" ADD COLUMN "score" real;
ALTER TABLE "cluster" ADD COLUMN "centroid" real[];
ALTER TABLE "cluster" ADD COLUMN "model" varchar;
ALTER TABLE "cluster" ADD COLUMN "dimensions" int;

ALTER TABLE "cluster"
    ADD CONSTRAINT "cluster_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    ADD CONSTRAINT "cluster_person_uuid_fkey" FOREIGN KEY ("person_uuid") REFERENCES "person" ("uuid") ON DELETE SET NULL,
    ADD CONSTRAINT "cluster_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    ADD CONSTRAINT "cluster_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL;

-- The upsert key, so a re-run replaces its own proposals rather than appending a second full set -
-- exactly the bug V2.43 fixed for detections. Rows with a NULL asset_uuid are exempt by SQL NULL
-- semantics, which is intended: human-authored and phase-2 library-wide clusters are not upsert
-- targets. That is also why this cannot fail on existing rows - every one of them has a NULL
-- asset_uuid and is therefore distinct.
ALTER TABLE "cluster" ADD CONSTRAINT "cluster_producer_key" UNIQUE ("asset_uuid", "node_kind", "cluster_index");

CREATE INDEX "idx_cluster_status" ON "cluster" ("status");
CREATE INDEX "idx_cluster_type_status" ON "cluster" ("type", "status");
CREATE INDEX "idx_cluster_asset_uuid" ON "cluster" ("asset_uuid");
CREATE INDEX "idx_cluster_person_uuid" ON "cluster" ("person_uuid");

-- Every row that exists at this point was authored by a human through the CRUD endpoint or seeded
-- by DemoDatabaseInitializer - those are the only two writers this table has ever had. Leaving
-- them PENDING would drop them into a review queue nobody proposed them to.
UPDATE "cluster" SET "status" = 'CONFIRMED' WHERE "asset_uuid" IS NULL;

-- ---------------------------------------------------------------------------
-- embedding_cluster: a join carrying no facts.
--
-- An auto-assignment and a human correction were indistinguishable, and a member could not be
-- re-assigned with an audit trail. Both FKs already cascade (V2.12 + V2.51).
-- ---------------------------------------------------------------------------
ALTER TABLE "embedding_cluster" ADD COLUMN "confidence" real;
ALTER TABLE "embedding_cluster" ADD COLUMN "origin" varchar NOT NULL DEFAULT 'AUTO';
ALTER TABLE "embedding_cluster" ADD COLUMN "created" timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now();
ALTER TABLE "embedding_cluster" ADD CONSTRAINT "embedding_cluster_origin_check" CHECK ("origin" IN ('AUTO', 'MANUAL'));

-- The primary key is (embedding_uuid, cluster_uuid), so "list the members of this cluster" - the
-- one query the review UI makes on every card - had no usable index.
CREATE INDEX "idx_embedding_cluster_cluster_uuid" ON "embedding_cluster" ("cluster_uuid");

-- ---------------------------------------------------------------------------
-- tag_cluster: the other half of the V2.51 bug.
--
-- V2.51 gave embedding_cluster.cluster_uuid ON DELETE CASCADE and left tag_cluster alone, so a
-- tagged cluster still cannot be deleted at all. Harmless while nothing deleted clusters; the
-- clusterer retires its own stale proposals, so it stops being harmless here.
-- ---------------------------------------------------------------------------
ALTER TABLE "tag_cluster" DROP CONSTRAINT IF EXISTS "tag_cluster_cluster_uuid_fkey";
ALTER TABLE "tag_cluster"
    ADD CONSTRAINT "tag_cluster_cluster_uuid_fkey" FOREIGN KEY ("cluster_uuid") REFERENCES "cluster" ("uuid") ON DELETE CASCADE;

-- ---------------------------------------------------------------------------
-- Face crops. attachment is already "the sink for node-produced derived binaries" (V2.44), with
-- content-addressed bytes in attachment_binary, node provenance, a "variant" discriminator, and
-- machine-nullable audit columns (V2.47). A face crop is precisely that, so it needs no new table
-- - only a target column, because a crop belongs to a detection rather than to a whole asset.
--
-- This is what retires the third-party avatar service the review UI fetched face crops from. Face
-- embeddings are biometric identifiers; the crops must not leave the deployment.
--
-- The new enum value is added but deliberately NOT used below: PostgreSQL forbids using a value in
-- the same transaction that adds it, and Flyway runs this file as one.
-- ---------------------------------------------------------------------------
ALTER TYPE "attachment_type" ADD VALUE IF NOT EXISTS 'FACE_CROP';

ALTER TABLE "attachment" ADD COLUMN "detection_uuid" uuid;
ALTER TABLE "attachment"
    ADD CONSTRAINT "attachment_detection_uuid_fkey" FOREIGN KEY ("detection_uuid") REFERENCES "detection" ("uuid") ON DELETE CASCADE;

-- V2.44's attachment_asset_variant_key is one row per (asset, type, node_kind, variant), which
-- cannot serve here: an asset has many face crops, one per detection. Key them by the detection.
CREATE UNIQUE INDEX "attachment_detection_variant_key"
    ON "attachment" ("detection_uuid", "type", "node_kind", "variant")
    WHERE "detection_uuid" IS NOT NULL;

CREATE INDEX "idx_attachment_detection_uuid" ON "attachment" ("detection_uuid");

-- ---------------------------------------------------------------------------
-- Documentation
-- ---------------------------------------------------------------------------
COMMENT ON TABLE "cluster" IS 'A group of embeddings that a producer believes belong to one subject, carrying a human confirm/reject decision. type=''face'' clusters are proposed per asset by the facedetect node and confirmed into a person.';
COMMENT ON COLUMN "cluster"."name" IS 'Human-supplied label. NULL for a machine proposal nobody has named yet. Unique per (type, name) when set, so two cluster types may share a name.';
COMMENT ON COLUMN "cluster"."status" IS 'PENDING (awaiting review), CONFIRMED (linked to a person) or REJECTED (not a real subject).';
COMMENT ON COLUMN "cluster"."person_uuid" IS 'The person a reviewer confirmed this cluster to be. SET NULL on person delete: the review record outlives the person row.';
COMMENT ON COLUMN "cluster"."asset_uuid" IS 'The asset this cluster was computed within. NULL for a human-authored cluster or a phase-2 library-wide one.';
COMMENT ON COLUMN "cluster"."cluster_index" IS 'Ordinal of this cluster within (asset_uuid, node_kind), assigned deterministically by the producer so a re-run upserts rather than appends.';
COMMENT ON COLUMN "cluster"."node_kind" IS 'Producing node kind, e.g. facedetect. NULL for a human-authored cluster.';
COMMENT ON COLUMN "cluster"."producer_version" IS 'Version of the producer and its model, so a model change can retire stale proposals via WHERE node_kind = ? AND producer_version <> ?.';
COMMENT ON COLUMN "cluster"."score" IS 'Cohesion: mean cosine similarity of the members to the centroid. NULL for a singleton, which has nothing to cohere with.';
COMMENT ON COLUMN "cluster"."centroid" IS 'Unit-normalised mean of the member vectors. Only comparable against embeddings sharing this row''s (type, model, dimensions).';
COMMENT ON COLUMN "cluster"."model" IS 'Model the member embeddings were produced by; qualifies "centroid".';
COMMENT ON COLUMN "cluster"."dimensions" IS 'Length of "centroid"; qualifies it together with "model".';
COMMENT ON COLUMN "cluster"."creator_uuid" IS 'NULL when written by a Cortex worker rather than a user (see cortex_instance)';
COMMENT ON COLUMN "cluster"."editor_uuid" IS 'NULL when written by a Cortex worker rather than a user (see cortex_instance)';
COMMENT ON CONSTRAINT "cluster_producer_key" ON "cluster" IS 'Idempotency key for machine-written clusters. Rows with a NULL asset_uuid are exempt by SQL NULL semantics, which is intended.';

COMMENT ON COLUMN "embedding_cluster"."origin" IS 'AUTO when the clusterer assigned this member, MANUAL when a reviewer moved it.';
COMMENT ON COLUMN "embedding_cluster"."confidence" IS 'Cosine similarity of this member to the cluster centroid at assignment time.';
COMMENT ON COLUMN "embedding_cluster"."created" IS 'When the membership was recorded, so a human correction is distinguishable from the original assignment.';

COMMENT ON COLUMN "attachment"."detection_uuid" IS 'The detection this binary depicts, for a FACE_CROP. NULL for every other attachment type.';
