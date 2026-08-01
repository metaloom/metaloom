-- Give a library a storage backend, so "where do these bytes go" has an answer before an upload
-- happens rather than after.
--
-- asset_pool already carries the filesystem-vs-S3 discriminator (V2.20: fs_path XOR s3_bucket, with
-- a CHECK constraint enforcing exactly one). What was missing was the route from an incoming upload
-- to a pool: the upload endpoint takes a libraryUuid, and nothing connected a library to a pool, so
-- every byte landed in the process-wide LOOM_STORAGE_UPLOAD_DIR and asset_location.pool_uuid was
-- never written by anything.
--
-- Placement rationale: the discriminator stays on asset_pool - it is the storage backend, and one
-- bucket is shared by many libraries. The library only points at one. Duplicating bucket/region/
-- endpoint onto library would have made asset_pool dead weight and forced operators to re-enter the
-- same endpoint per library.
--
-- pool_uuid is NULLABLE on purpose. NULL means "the legacy local upload directory", which is what
-- every existing installation is already doing; this migration must not invent a pool for them.
--
-- ON DELETE RESTRICT, not SET NULL: silently re-pointing a library at the local disk after its
-- bucket row was deleted would scatter an asset's bytes across two backends with no record of it.

ALTER TABLE "library" ADD COLUMN "pool_uuid" uuid;
ALTER TABLE "library"
    ADD CONSTRAINT "library_pool_uuid_fkey"
    FOREIGN KEY ("pool_uuid") REFERENCES "asset_pool" ("uuid") ON DELETE RESTRICT;

COMMENT ON COLUMN "library"."pool_uuid" IS
'Storage backend for binaries uploaded into this library. NULL = the process-wide local upload
directory (LOOM_STORAGE_UPLOAD_DIR). The pool row decides filesystem vs S3; see asset_pool.';

-- Attachments are content-addressed by sha512sum, so the object key is derivable and only the
-- backend has to be recorded. Same NULL semantics as library.pool_uuid.
ALTER TABLE "attachment_binary" ADD COLUMN "pool_uuid" uuid;
ALTER TABLE "attachment_binary"
    ADD CONSTRAINT "attachment_binary_pool_uuid_fkey"
    FOREIGN KEY ("pool_uuid") REFERENCES "asset_pool" ("uuid") ON DELETE RESTRICT;

COMMENT ON COLUMN "attachment_binary"."pool_uuid" IS
'Storage backend holding the bytes for this sha512sum. NULL = the local upload directory.';

-- Deleting a binary now has to answer "is anything else still pointing at these bytes?" before it
-- unlinks them. That question is (pool_uuid, path); the V2.10 index on (path) alone cannot serve it
-- once the same relative key exists in more than one pool.
CREATE INDEX "asset_location_pool_path_idx" ON "asset_location" ("pool_uuid", "path");

COMMENT ON COLUMN "asset_location"."path" IS
'Where the bytes live *within the pool*: a filesystem path for filesystem pools (absolute for the
legacy NULL-pool case), an object key for S3 pools. Not a URL - the pool supplies bucket, region and
endpoint.';
