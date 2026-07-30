-- Deduplication review model (spec/features/pipeline-nodes/NODE_DEDUP_PLAN.md).
--
-- The fingerprint-dedup discovery node finds near-duplicate videos and reports candidate groups
-- here for human review; a user confirms/denies; the fingerprint-dedup-apply node acts only on
-- CONFIRMED groups. There was no asset-to-asset relation table before this: task + asset_task
-- cannot express keep-vs-dup roles or per-member similarity scores, so a purpose-built model is added.
--
-- One dedup_group == one candidate duplicate set (one KEEP member + one-or-more DUP members),
-- mirroring xdb-clean's best-vs-dups result and generalising the pair case.
--
--   keep_asset_uuid   is denormalised for convenience; the authoritative KEEP is the member with
--                     role='KEEP'. The DAO keeps them consistent.
--   ON DELETE SET NULL on keep_asset_uuid but ON DELETE CASCADE on the member: deleting the kept
--                     asset must not silently delete the whole review record, but a member row is
--                     meaningless without its asset.
--   size / zero_chunk_count are snapshots taken at discovery time so the review UI and the apply
--                     node's safeguards need not re-read every asset; the apply node still
--                     re-verifies against the live file before moving anything.

CREATE TYPE "dedup_status" AS ENUM ('PENDING', 'CONFIRMED', 'REJECTED');

CREATE TABLE "dedup_group" (
    "uuid"            uuid NOT NULL DEFAULT uuid_generate_v4(),
    "algorithm"       varchar NOT NULL,
    "status"          "dedup_status" NOT NULL DEFAULT 'PENDING',
    "keep_asset_uuid" uuid,
    "score"           real,
    "meta"            jsonb,

    "created"         timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"    uuid,
    "edited"          timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"     uuid,

    CONSTRAINT "dedup_group_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "dedup_group_keep_asset_uuid_fkey" FOREIGN KEY ("keep_asset_uuid") REFERENCES "asset" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "dedup_group_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "dedup_group_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid")
);

CREATE TABLE "dedup_group_member" (
    "uuid"             uuid NOT NULL DEFAULT uuid_generate_v4(),
    "group_uuid"       uuid NOT NULL,
    "asset_uuid"       uuid NOT NULL,
    "role"             varchar NOT NULL,
    "score"            real,
    "size"             bigint,
    "zero_chunk_count" bigint,

    CONSTRAINT "dedup_group_member_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "dedup_group_member_group_uuid_fkey" FOREIGN KEY ("group_uuid") REFERENCES "dedup_group" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "dedup_group_member_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "dedup_group_member_role_check" CHECK ("role" IN ('KEEP', 'DUP')),
    CONSTRAINT "dedup_group_member_unique" UNIQUE ("group_uuid", "asset_uuid")
);

CREATE INDEX "idx_dedup_group_status" ON "dedup_group" ("status");
CREATE INDEX "idx_dedup_group_member_asset" ON "dedup_group_member" ("asset_uuid");
CREATE INDEX "idx_dedup_group_member_group" ON "dedup_group_member" ("group_uuid");

COMMENT ON TABLE "dedup_group" IS 'A candidate duplicate set (one KEEP + N DUP members) discovered via fingerprint similarity, awaiting/holding a human confirm/deny decision.';
COMMENT ON COLUMN "dedup_group"."algorithm" IS 'Fingerprint algorithm the similarity was computed with, e.g. metaloom-multisector-v1.';
COMMENT ON COLUMN "dedup_group"."status" IS 'PENDING (awaiting review), CONFIRMED (apply node may act) or REJECTED (never act).';
COMMENT ON COLUMN "dedup_group"."keep_asset_uuid" IS 'Denormalised pointer to the KEEP member; authoritative KEEP is the member with role=KEEP.';
COMMENT ON COLUMN "dedup_group"."score" IS 'Representative (minimum member) similarity score for the group.';
COMMENT ON COLUMN "dedup_group"."creator_uuid" IS 'NULL when machine-created by the discovery node.';
COMMENT ON COLUMN "dedup_group_member"."role" IS 'KEEP (the asset to keep) or DUP (a candidate duplicate to move).';
COMMENT ON COLUMN "dedup_group_member"."size" IS 'File size snapshot at discovery time (safeguard hint; apply re-verifies live).';
COMMENT ON COLUMN "dedup_group_member"."zero_chunk_count" IS 'Completeness snapshot at discovery time; 0 means complete.';
