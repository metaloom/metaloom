-- Who, among the people holding one share link, wrote a piece of feedback.
--
-- V2.99 made identity the link: "anyone holding the link may edit or delete anything written
-- through that link". That held only while a link reached exactly one person. Links get forwarded -
-- to a colleague, a producer, a whole agency - and then every visitor could delete every other
-- visitor's comments, annotations and reactions with a request naming the row's uuid. The UI
-- softened it by matching on author_name, but author_name is the link's single visitor name
-- (V2.97), so two people holding the same link shared it, and the server never checked anything.
--
-- visitor_id is a random id minted when a session is opened and carried inside the signed session
-- token (ShareSessionTokens). A visitor may edit and delete only rows carrying their own id; the
-- public listings answer `own` per row so the viewer can show the controls only where they work.
-- A session that lapses and is reopened from the same browser keeps its id - the server reads it
-- back from the previous, correctly signed token - so a reviewer is not cut off from their own
-- notes by the twelve-hour session limit.
--
-- NULL on rows written before this migration. Nobody can prove they wrote those, so through the
-- link they become read-only. The link's owner, who is a Loom user, is unaffected.
ALTER TABLE "share_comment"    ADD COLUMN "visitor_id" uuid;
ALTER TABLE "share_annotation" ADD COLUMN "visitor_id" uuid;
ALTER TABLE "share_reaction"   ADD COLUMN "visitor_id" uuid;

COMMENT ON COLUMN "share_comment"."visitor_id"    IS 'The share visitor who wrote the comment, from the signed session token. NULL for rows predating V2.120, which no visitor may change';
COMMENT ON COLUMN "share_annotation"."visitor_id" IS 'The share visitor who drew the mark, from the signed session token. NULL for rows predating V2.120, which no visitor may change';
COMMENT ON COLUMN "share_reaction"."visitor_id"   IS 'The share visitor who reacted, from the signed session token. NULL for rows predating V2.120, which no visitor may change';

-- One reaction of a type per VISITOR per subject, not per link. Under the V2.99 key a second
-- reviewer's "approve" on an asset somebody else had already approved was silently swallowed by
-- ON CONFLICT DO NOTHING. A double click by the same visitor is still harmless.
--
-- Legacy rows have a NULL visitor_id and therefore never conflict with anything, which is right:
-- they were already unique per link under the old key.
DROP INDEX IF EXISTS "idx_share_reaction_asset_unique";
DROP INDEX IF EXISTS "idx_share_reaction_comment_unique";
DROP INDEX IF EXISTS "idx_share_reaction_annotation_unique";

CREATE UNIQUE INDEX "idx_share_reaction_asset_unique"
  ON "share_reaction" ("share_uuid", "visitor_id", "type", "asset_uuid") WHERE "asset_uuid" IS NOT NULL;
CREATE UNIQUE INDEX "idx_share_reaction_comment_unique"
  ON "share_reaction" ("share_uuid", "visitor_id", "type", "share_comment_uuid") WHERE "share_comment_uuid" IS NOT NULL;
CREATE UNIQUE INDEX "idx_share_reaction_annotation_unique"
  ON "share_reaction" ("share_uuid", "visitor_id", "type", "share_annotation_uuid") WHERE "share_annotation_uuid" IS NOT NULL;

COMMENT ON COLUMN "share_reaction"."share_uuid" IS 'Together with visitor_id, stands in for creator_uuid in the uniqueness key';
