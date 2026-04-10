CREATE TABLE "asset_pool" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "name" varchar NOT NULL,
  "meta" jsonb,

  -- Filesystem pool settings (NULL when S3 pool)
  "fs_path" varchar,

  -- S3 pool settings (NULL when filesystem pool)
  "s3_bucket" varchar,
  "s3_region" varchar,
  "s3_endpoint" varchar,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,
  PRIMARY KEY ("uuid")
);

CREATE UNIQUE INDEX ON "asset_pool" ("name");

ALTER TABLE "asset_pool" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "asset_pool" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

-- Ensure that either fs_path or s3_bucket is set, but not both
ALTER TABLE "asset_pool" ADD CONSTRAINT asset_pool_type_check CHECK (
  (fs_path IS NOT NULL AND s3_bucket IS NULL) OR (fs_path IS NULL AND s3_bucket IS NOT NULL)
);

COMMENT ON TABLE "asset_pool" IS 'Storage pools for asset binaries. A pool represents either a filesystem directory or an S3 bucket where binary data is stored.';
COMMENT ON COLUMN "asset_pool"."name" IS 'Unique human-readable name for the pool';
COMMENT ON COLUMN "asset_pool"."fs_path" IS 'Base filesystem path for the pool (only set for filesystem pools)';
COMMENT ON COLUMN "asset_pool"."s3_bucket" IS 'S3 bucket name (only set for S3 pools)';
COMMENT ON COLUMN "asset_pool"."s3_region" IS 'S3 region (only set for S3 pools)';
COMMENT ON COLUMN "asset_pool"."s3_endpoint" IS 'S3 endpoint URL for S3-compatible services (only set for S3 pools)';

-- Add pool reference to asset_location (the binary storage table)
ALTER TABLE "asset_location" ADD COLUMN "pool_uuid" uuid;
ALTER TABLE "asset_location" ADD FOREIGN KEY ("pool_uuid") REFERENCES "asset_pool" ("uuid");

-- Enforce one binary per asset
ALTER TABLE "asset_location" ADD CONSTRAINT asset_location_unique_asset UNIQUE ("asset_uuid");

COMMENT ON COLUMN "asset_location"."pool_uuid" IS 'Reference to the storage pool where the binary is stored';
