-- The last three foreign keys that kept an asset from being deleted.
--
-- V2.72 settled the tag link and V2.73 the membership/task/user-meta links. What still blocked a
-- delete was the social content written *about* an asset, plus its library membership:
--
--   comment       - a comment on an asset. The thread is about the asset and has nowhere to live
--                   without it, so it goes too. Replies and reactions on those comments follow
--                   through the cascades V2.35 already put on comment.parent_uuid and
--                   reaction.comment_uuid. Comments on TASKS and ANNOTATIONS are untouched.
--   reaction      - the same, one step simpler: a reaction to an asset is a reaction to nothing
--                   once the asset is gone. Reactions on tasks, comments and annotations stay.
--   library_asset - membership, exactly like collection_asset in V2.73. The row says the asset was
--                   found in that library; the LIBRARY survives with every other asset in it.
--
-- With this in place "DELETE FROM asset" no longer raises a foreign-key violation for any link the
-- system writes - see AssetCascadeTest, which asserts both halves: what dies with the asset, and
-- what must still be there afterwards.

ALTER TABLE "comment" DROP CONSTRAINT "comment_asset_uuid_fkey";
ALTER TABLE "comment" ADD CONSTRAINT "comment_asset_uuid_fkey"
  FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;

ALTER TABLE "reaction" DROP CONSTRAINT "reaction_asset_uuid_fkey";
ALTER TABLE "reaction" ADD CONSTRAINT "reaction_asset_uuid_fkey"
  FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;

ALTER TABLE "library_asset" DROP CONSTRAINT "library_asset_asset_uuid_fkey";
ALTER TABLE "library_asset" ADD CONSTRAINT "library_asset_asset_uuid_fkey"
  FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE;

-- library_asset."library_uuid" stays a plain reference: a library with assets in it must not be
-- deleted out from under them, which is the same stance V2.63 took for library -> asset_pool.

COMMENT ON CONSTRAINT "comment_asset_uuid_fkey" ON "comment" IS 'Deleting the asset deletes the comments written about it, and their replies; comments on tasks and annotations are unaffected';
COMMENT ON CONSTRAINT "reaction_asset_uuid_fkey" ON "reaction" IS 'Deleting the asset deletes the reactions to it; reactions on tasks, comments and annotations are unaffected';
COMMENT ON CONSTRAINT "library_asset_asset_uuid_fkey" ON "library_asset" IS 'Deleting the asset removes it from its libraries; the libraries survive, with every other asset in them';
