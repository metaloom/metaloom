-- Two defects that made endpoint and DAO tests fail against the shipped schema.
--
-- 1. asset_location UNIQUE (asset_uuid)
--
--    V2.10 created the table with the comment "Multiple asset_locations may share the
--    same asset thus the properties will be decoupled from asset" - that is the entire
--    reason the table exists. V2.20 then added UNIQUE (asset_uuid) under the heading
--    "Enforce one binary per asset", conflating two different things: one binary blob per
--    content hash (true by construction - the hash IS the content) and one filesystem
--    path per content hash (false, and the opposite of what a deduplicating catalog
--    needs).
--
--    It made the same file in two libraries unrepresentable, left HashDedupNode with
--    nowhere to record what it found, and broke every test that records more than one
--    location for the fixture asset.
--
--    The real natural key is the path within a library, which was never enforced: a
--    repeated scan could insert duplicate rows for the same path.
--
-- 2. reaction/comment -> annotation had no ON DELETE CASCADE
--
--    Deleting an annotation that carries a reaction or a comment failed with a foreign
--    key violation, so DELETE_ANNOTATION was unusable for any annotation anyone had
--    reacted to. A reaction to a deleted annotation has no meaning, so it goes with it.

ALTER TABLE "asset_location" DROP CONSTRAINT IF EXISTS "asset_location_unique_asset";

ALTER TABLE "asset_location"
    ADD CONSTRAINT "asset_location_unique_library_path" UNIQUE ("library_uuid", "path");

COMMENT ON TABLE "asset_location" IS
'Media found by the scanner. Several locations may share one asset - that is the point of
the table: the same content can live at several paths and in several libraries. The
natural key is (library_uuid, path). If a canonical location is ever needed, model it as
a pointer on asset or a partial unique index on an is_primary flag, not by constraining
this table to one row per asset.';

ALTER TABLE "reaction" DROP CONSTRAINT IF EXISTS "reaction_annotation_uuid_fkey";
ALTER TABLE "reaction"
    ADD CONSTRAINT "reaction_annotation_uuid_fkey"
    FOREIGN KEY ("annotation_uuid") REFERENCES "annotation" ("uuid") ON DELETE CASCADE;

ALTER TABLE "comment" DROP CONSTRAINT IF EXISTS "comment_annotation_uuid_fkey";
ALTER TABLE "comment"
    ADD CONSTRAINT "comment_annotation_uuid_fkey"
    FOREIGN KEY ("annotation_uuid") REFERENCES "annotation" ("uuid") ON DELETE CASCADE;
