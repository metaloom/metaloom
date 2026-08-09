-- Deleting a collection removes its membership rows; the assets survive.
--
-- V2.73 asserted in its own comment that "the other direction is already handled: collection ->
-- collection_asset ... unaffected by this migration". That was wrong. V2.8 created both foreign keys
-- on "collection_asset" as plain references, and V2.73 only replaced the asset-side one. The
-- collection side has therefore always blocked: DELETE /collections/:uuid on a collection with any
-- member at all failed with a foreign-key violation, which surfaces over REST as a 500.
--
-- Nothing noticed because collection membership had no REST surface and no DAO caller outside a
-- cascade test, so the only collections anyone deleted were empty ones. Adding the membership routes
-- and the "assign" node makes non-empty collections the normal case.
--
-- A membership row is not content: it says "this asset is in that collection" and is meaningless
-- once the collection is gone. The asset is untouched and keeps every other collection it is in --
-- exactly the argument V2.73 made for the asset side.
--
-- The library side is deliberately NOT changed. "library_asset"."library_uuid" still blocks, because
-- a library must not be deleted out from under the assets in it; that asymmetry is intentional and
-- is documented in the domain model.

ALTER TABLE "collection_asset" DROP CONSTRAINT "collection_asset_collection_uuid_fkey";
ALTER TABLE "collection_asset" ADD CONSTRAINT "collection_asset_collection_uuid_fkey"
  FOREIGN KEY ("collection_uuid") REFERENCES "collection" ("uuid") ON DELETE CASCADE;

COMMENT ON CONSTRAINT "collection_asset_collection_uuid_fkey" ON "collection_asset" IS 'Deleting the collection removes its membership rows; the assets survive, along with every other collection they are in';
