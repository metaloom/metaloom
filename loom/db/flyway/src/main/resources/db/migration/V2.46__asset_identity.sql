-- Fix asset identity and add the consistency flag.
--
-- asset had PRIMARY KEY (sha512sum) plus a separate unique uuid, and EVERY child table
-- foreign-keys uuid - not the primary key. Two identity columns had to be kept
-- consistent forever, the primary key was a 128 character varchar in every index, and
-- uuid was nullable despite being the foreign key target of a dozen tables. All the new
-- result tables reference asset(uuid), so this is settled before they multiply.
--
-- Verified before writing this migration: no child table references asset(sha512sum).

-- 1. uuid becomes the real primary key ---------------------------------------
UPDATE "asset" SET "uuid" = uuid_generate_v4() WHERE "uuid" IS NULL;
ALTER TABLE "asset" ALTER COLUMN "uuid" SET NOT NULL;

ALTER TABLE "asset" DROP CONSTRAINT "asset_pkey";
-- Promote the existing unique index rather than building a second one: the foreign keys
-- of every child table depend on asset_uuid_idx, and USING INDEX keeps that same index
-- object (Postgres renames it to the constraint name).
ALTER TABLE "asset" ADD CONSTRAINT "asset_pkey" PRIMARY KEY USING INDEX "asset_uuid_idx";

-- 2. sha512sum stays the content identity, now as a natural key --------------
ALTER TABLE "asset" ADD CONSTRAINT "asset_sha512sum_key" UNIQUE ("sha512sum");

-- 3. ConsistencyNode's second output -----------------------------------------
ALTER TABLE "asset" ADD COLUMN "is_complete" boolean;

-- 4. Drop the superseded inline S3 pointer (asset_location.pool_uuid replaces it)
ALTER TABLE "asset" DROP COLUMN IF EXISTS "s3_bucket_name";
ALTER TABLE "asset" DROP COLUMN IF EXISTS "s3_object_path";

COMMENT ON TABLE "asset" IS
'The binary/content component of an asset, addressed by its SHA-512.

IDENTITY RULE: sha512sum stays NOT NULL, so an asset row cannot exist before a hashing
node has run. That is deliberate - the node system already assumes SHA-512 is available
(AbstractMediaNode fetches the asset by SHA-512 in its lifecycle), and pipeline_run_item
carries the pre-hash identity (media_path plus a nullable sha512). Nodes upstream of
hashing hold their outputs in pipeline_node_task.outputs, and the sync flushes them once
identity exists.

PLACEMENT RULE: intrinsic properties of the BYTES (hashes, size, zero_chunk_count,
is_complete) live here. Everything derived by interpretation lives in a component table
(asset_*_comp), keyed by its producing node.';

COMMENT ON COLUMN "asset"."uuid" IS 'Primary key. Every child table references this column.';
COMMENT ON COLUMN "asset"."sha512sum" IS 'Content identity. Natural key, unique and not null.';
COMMENT ON COLUMN "asset"."is_complete" IS 'ConsistencyNode verdict. NULL = not yet checked.';
COMMENT ON COLUMN "asset"."zero_chunk_count" IS 'ConsistencyNode zero-chunk count, used to detect truncated files';
