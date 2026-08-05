-- Deleting an asset removes the rows that only describe where it sat and what one user wrote onto it.
--
-- V2.72 settled the tag link; these are the three remaining plain foreign keys on "asset" that a
-- normal library actually populates, and each one of them blocked the delete outright (a foreign-key
-- violation, which surfaces over REST as a 500). None of the three is content in its own right:
--
--   collection_asset  - membership. The row says "this asset is in that collection", so it is
--                       meaningless without the asset. The COLLECTION is untouched and keeps every
--                       other asset in it.
--   asset_task        - the assets a task is about. A task may reference several assets (the table
--                       has been many-to-many since V2.8), so losing one asset drops that one link
--                       and leaves the TASK - its title, status, comments and assignees - in place,
--                       still pointing at whatever else it referenced.
--   asset_user_meta   - per-user metadata on this asset. It has no meaning once the asset is gone.
--
-- The other direction is already handled: collection -> collection_asset and task -> asset_task are
-- unaffected by this migration (deleting a task cascades to asset_task since V2.35).
--
-- Still deliberately blocking after this: "comment" and "reaction" carry an asset_uuid, and whether
-- a discussion about an asset should disappear with it is a content decision, not a plumbing one.
-- "library_asset" also still blocks, but nothing writes it yet.

ALTER TABLE "collection_asset" DROP CONSTRAINT "collection_asset_asset_uuid_fkey";
ALTER TABLE "collection_asset" ADD CONSTRAINT "collection_asset_asset_uuid_fkey"
  FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;

ALTER TABLE "asset_task" DROP CONSTRAINT "asset_task_asset_uuid_fkey";
ALTER TABLE "asset_task" ADD CONSTRAINT "asset_task_asset_uuid_fkey"
  FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;

ALTER TABLE "asset_user_meta" DROP CONSTRAINT "asset_user_meta_asset_uuid_fkey";
ALTER TABLE "asset_user_meta" ADD CONSTRAINT "asset_user_meta_asset_uuid_fkey"
  FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;

-- asset_user_meta."user_uuid" stays a plain reference, for the same reason tag_asset."creator_uuid"
-- does (V2.72): a user is not deleted out from under their work.

COMMENT ON CONSTRAINT "collection_asset_asset_uuid_fkey" ON "collection_asset" IS 'Deleting the asset removes it from its collections; the collections survive';
COMMENT ON CONSTRAINT "asset_task_asset_uuid_fkey" ON "asset_task" IS 'Deleting the asset removes it from its tasks; the tasks survive, along with any other assets they reference';
COMMENT ON CONSTRAINT "asset_user_meta_asset_uuid_fkey" ON "asset_user_meta" IS 'Deleting the asset removes the per-user metadata written onto it';
