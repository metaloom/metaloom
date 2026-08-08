-- Gives the workflow star rating its own reaction type, and indexes the read the filter node makes.
--
-- The manual-sort review screen writes a 1-10 rating as an asset reaction. Until now it borrowed
-- SATISFIED as a marker type, because reaction.type is not nullable in practice - the REST read
-- path runs it through ReactionType.valueOf. That borrowing had a cost: the UNIQUE index on
-- (creator_uuid, type, asset_uuid) made a star rating and a genuine emoji reaction the same row,
-- so rating an asset silently overwrote a person's reaction to it, and reacting overwrote their
-- rating. With RATING as its own constant the index means exactly what it should - one rating per
-- user per asset, one reaction of each kind per user per asset, and no interference between them.
--
-- Scoped to asset_uuid IS NOT NULL: a reaction on a comment or an annotation is not a workflow
-- rating even if it happens to carry a number, and only the asset routes are reachable from the
-- review screen.
--
-- The NOT EXISTS guard is for a hand-edited database. Nothing has ever written 'RATING', so on any
-- database this instance produced there is nothing to collide with. A row the guard skips is a
-- duplicate rating by one creator on one asset; it is left alone rather than deleted, because a
-- migration that discards a person's decision is worse than one that leaves a stray row readable
-- as exactly what it was.
UPDATE "reaction" r
   SET "type" = 'RATING'
 WHERE r."type" = 'SATISFIED'
   AND r."rating" IS NOT NULL
   AND r."asset_uuid" IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
         FROM "reaction" x
        WHERE x."creator_uuid" = r."creator_uuid"
          AND x."asset_uuid" = r."asset_uuid"
          AND x."type" = 'RATING');

-- The consumer query is "the reactions on this asset". Every index that mentions asset_uuid today
-- leads with creator_uuid, which cannot serve it, so that read is a sequential scan - and the
-- filter node's per-item rating lookup makes it hot.
CREATE INDEX "idx_reaction_asset_type" ON "reaction" ("asset_uuid", "type");

COMMENT ON COLUMN "reaction"."type" IS
  'A ReactionType constant name; anything else makes every REST read of the row a 500. RATING marks a workflow star rating, whose value is in "rating".';
