-- Make attachment the sink for node-produced derived binaries.
--
-- ThumbnailNode produces contact sheets and had nowhere to record them: attachment
-- existed with attachment_type = ASSET_THUMBNAIL, but carried no node provenance and no
-- discriminator, so thumbnails from different node versions were indistinguishable and
-- an asset could not have both a contact sheet and a poster frame. Derived binaries are
-- a general category - proxies, waveforms, extracted audio - and every one of them would
-- have hit the same wall.
--
-- This migration is ADDITIVE: attachment_binary holds real content-addressed rows and
-- attachment is referenced by the embedding endpoints.

ALTER TYPE "attachment_type" ADD VALUE IF NOT EXISTS 'CONTACT_SHEET';
ALTER TYPE "attachment_type" ADD VALUE IF NOT EXISTS 'POSTER_FRAME';
ALTER TYPE "attachment_type" ADD VALUE IF NOT EXISTS 'WAVEFORM';
ALTER TYPE "attachment_type" ADD VALUE IF NOT EXISTS 'PROXY';
ALTER TYPE "attachment_type" ADD VALUE IF NOT EXISTS 'EXTRACTED_AUDIO';

ALTER TABLE "attachment"
    ADD COLUMN "node_kind"        varchar,
    ADD COLUMN "node_id"          varchar,
    ADD COLUMN "producer_version" varchar NOT NULL DEFAULT '',
    ADD COLUMN "variant"          varchar NOT NULL DEFAULT '',
    ADD COLUMN "run_uuid"         uuid,
    ADD COLUMN "task_uuid"        uuid;

ALTER TABLE "attachment"
    ADD CONSTRAINT "attachment_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
    ADD CONSTRAINT "attachment_task_uuid_fkey" FOREIGN KEY ("task_uuid") REFERENCES "pipeline_node_task" ("uuid") ON DELETE SET NULL;

-- asset_uuid is nullable (an attachment may hang off an embedding instead), so the
-- idempotency key has to be a partial index rather than a table constraint.
CREATE UNIQUE INDEX "attachment_asset_variant_key"
    ON "attachment" ("asset_uuid", "type", "node_kind", "variant")
    WHERE "asset_uuid" IS NOT NULL AND "node_kind" IS NOT NULL;

-- NO "exactly one target" constraint. The schema audit suggested one, but the two
-- targets are not alternatives: an EMBEDDING_ATTACHMENT carries the embedding it depicts
-- AND the asset that embedding was extracted from, which is what the test fixture and the
-- embedding endpoints rely on. asset_uuid answers "which asset does this binary belong
-- to", embedding_uuid answers "which embedding produced it", and a thumbnail of an asset
-- sets only the former.

-- A node-produced thumbnail must not block deletion of the asset it depicts.
ALTER TABLE "attachment" DROP CONSTRAINT IF EXISTS "attachment_asset_uuid_fkey";
ALTER TABLE "attachment"
    ADD CONSTRAINT "attachment_asset_uuid_fkey"
    FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;

CREATE INDEX "idx_attachment_asset_uuid" ON "attachment" ("asset_uuid");

COMMENT ON COLUMN "attachment"."node_kind" IS 'Producing node kind, e.g. thumbnail. NULL for user-uploaded attachments.';
COMMENT ON COLUMN "attachment"."producer_version" IS 'Model/algorithm version of the producing node';
COMMENT ON COLUMN "attachment"."variant" IS 'Sub-division within (asset, type, node_kind): sheet-1, t=00:10, ...';
COMMENT ON COLUMN "attachment"."run_uuid" IS 'Pipeline run that produced this binary, when it was node-produced';

COMMENT ON COLUMN "annotation"."thumbnail" IS 'Superseded by an attachment of type POSTER_FRAME; kept until the annotation UI migrates.';
