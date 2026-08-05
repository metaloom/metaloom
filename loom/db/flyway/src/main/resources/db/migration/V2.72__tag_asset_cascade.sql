-- Deleting a tagged asset, or a tag that is in use, removes the assignment - not the other object.
--
-- Both foreign keys of "tag_asset" were plain references (V2.8), so the join row blocked the delete of
-- either side: deleting an asset somebody had tagged failed with a foreign-key violation, which
-- surfaces over REST as a 500. `AssetCascadeTest` pinned that as the known state.
--
-- A tag assignment is not an object in its own right - it is a statement that this tag applies to that
-- asset. When either side goes, the statement is meaningless and must go with it. What must NOT go is
-- the other side: deleting an asset leaves the tag in the catalog for every other asset that carries
-- it, and deleting a tag leaves the assets untouched.
--
-- The tag itself is deliberately not reference-counted: a tag with no assignments is an empty tag, not
-- a deleted one, and curators create tags before using them.

ALTER TABLE "tag_asset" DROP CONSTRAINT "tag_asset_asset_uuid_fkey";
ALTER TABLE "tag_asset" ADD CONSTRAINT "tag_asset_asset_uuid_fkey"
  FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;

ALTER TABLE "tag_asset" DROP CONSTRAINT "tag_asset_tag_uuid_fkey";
ALTER TABLE "tag_asset" ADD CONSTRAINT "tag_asset_tag_uuid_fkey"
  FOREIGN KEY ("tag_uuid") REFERENCES "tag" ("uuid") ON DELETE CASCADE;

-- creator_uuid stays a plain reference on purpose: a user is not deleted out from under their work,
-- and if that ever changes the placement should lose its attribution rather than be deleted.

COMMENT ON CONSTRAINT "tag_asset_asset_uuid_fkey" ON "tag_asset" IS 'Deleting the asset removes its tag assignments; the tags themselves survive for every other asset carrying them';
COMMENT ON CONSTRAINT "tag_asset_tag_uuid_fkey" ON "tag_asset" IS 'Deleting the tag removes its assignments; the assets survive';
