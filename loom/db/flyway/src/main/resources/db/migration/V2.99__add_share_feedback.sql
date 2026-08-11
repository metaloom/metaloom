-- What the customer said back: comments, reactions and drawn-on annotations left through a share
-- link (V2.97) by somebody with no Loom account.
--
-- WHY THESE ARE NOT `comment`, `reaction` AND `annotation`.
--
-- The blocking reason is structural, not stylistic. All three existing tables declare
-- `creator_uuid uuid NOT NULL REFERENCES "user"("uuid")`. A share visitor has no user row and must
-- not be given one - auto-provisioning an account for anybody who opens a link would put unnamed,
-- unauthenticated rows into the table that RBAC, `/me`, group membership and the notification
-- fan-out all treat as people. Making creator_uuid nullable on the shared tables instead would
-- weaken a NOT NULL that thirty query paths currently rely on, to serve a case none of them mean.
--
-- The second reason is that these are not the same kind of statement. An internal comment is a
-- colleague's note inside the system of record; this is an outside party's opinion, captured
-- through a capability URL, and it must stay visibly separate everywhere it surfaces - in the UI,
-- in exports, and above all before it reaches the chat agent, which treats comment text as
-- trustworthy input today (see AGENTIC_CHAT_PLAN.md on comments as a prompt-injection surface;
-- guest comments make that concrete).
--
-- The third is the reaction key. `reaction` is UNIQUE (creator_uuid, type, <subject>_uuid), and
-- V2.78 exists because two different features sharing that index silently overwrote each other's
-- rows. Adding a fourth polymorphic subject FK to a table DB_INTEGRITY.md already flags for
-- polymorphic references, keyed on a creator_uuid that would have to be null, is the same mistake
-- with more rows.
--
-- IDENTITY IS THE SHARE, NOT THE PERSON. Per V2.97 there is one visitor_name per link, so
-- authorship here is "whoever held this link". author_name is denormalised onto every row rather
-- than joined from share.visitor_name: what somebody was called when they wrote something is a
-- historical fact, and it must not change retroactively if the share row is later edited.
-- The practical consequence, which the UI states plainly: anyone holding the link may edit or
-- delete anything written through that link.

-- Annotations first: share_comment references them, so they must exist to be pointed at.
CREATE TABLE "share_annotation" (
  "uuid"           uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "share_uuid"     uuid NOT NULL,
  "asset_uuid"     uuid NOT NULL,

  -- What the reviewer marked. A timecode ("the cut at 0:14 is early"), a box ("this logo"), or
  -- both ("this logo, between 0:14 and 0:19"). varchar + CHECK, per V2.55.
  "kind"           varchar NOT NULL,

  -- SECONDS as a float, not the integer the internal `annotation` table uses. That column predates
  -- any video player and cannot express a frame boundary: at 25fps an integer second is 25 frames
  -- of ambiguity, which is exactly the precision a reviewer marking a cut point is trying to
  -- communicate.
  "time_from"      double precision,
  "time_to"        double precision,

  -- NORMALISED 0..1 against the media's own dimensions, not pixels. The viewer is full-bleed and
  -- responsive, so a pixel box drawn on a 1400px-wide laptop means nothing on a phone; and
  -- ZoomableImage already emits normalised regions, so this is the coordinate space the UI hands
  -- over unchanged. The internal `annotation` table stores pixels, which is why its regions cannot
  -- be reused here.
  "area_x"         double precision,
  "area_y"         double precision,
  "area_width"     double precision,
  "area_height"    double precision,

  "text"           varchar,
  "author_name"    varchar NOT NULL,

  "created"        timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "edited"         timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),

  CONSTRAINT "share_annotation_pkey" PRIMARY KEY ("uuid"),

  CONSTRAINT "share_annotation_kind_check" CHECK ("kind" IN ('TEMPORAL', 'SPATIAL', 'SPATIOTEMPORAL')),

  -- Each kind must actually carry the geometry it claims. Without this a SPATIAL annotation with
  -- no box renders as nothing and looks like a UI bug.
  CONSTRAINT "share_annotation_geometry_check" CHECK (
    ("kind" = 'TEMPORAL'       AND "time_from" IS NOT NULL AND "area_x" IS NULL) OR
    ("kind" = 'SPATIAL'        AND "area_x" IS NOT NULL AND "area_y" IS NOT NULL
                               AND "area_width" IS NOT NULL AND "area_height" IS NOT NULL) OR
    ("kind" = 'SPATIOTEMPORAL' AND "time_from" IS NOT NULL AND "area_x" IS NOT NULL
                               AND "area_y" IS NOT NULL AND "area_width" IS NOT NULL
                               AND "area_height" IS NOT NULL)),

  -- Normalised means normalised. A box outside the frame is a bug in the writer, and catching it
  -- here is cheaper than rendering it somewhere off-screen and wondering where the marker went.
  CONSTRAINT "share_annotation_area_range_check" CHECK (
    ("area_x"      IS NULL OR ("area_x"      >= 0 AND "area_x"      <= 1)) AND
    ("area_y"      IS NULL OR ("area_y"      >= 0 AND "area_y"      <= 1)) AND
    ("area_width"  IS NULL OR ("area_width"  >  0 AND "area_width"  <= 1)) AND
    ("area_height" IS NULL OR ("area_height" >  0 AND "area_height" <= 1))),

  CONSTRAINT "share_annotation_time_order_check" CHECK (
    "time_to" IS NULL OR "time_from" IS NULL OR "time_to" >= "time_from"),

  CONSTRAINT "share_annotation_share_fkey" FOREIGN KEY ("share_uuid") REFERENCES "share" ("uuid") ON DELETE CASCADE,
  CONSTRAINT "share_annotation_asset_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE
);

CREATE TABLE "share_comment" (
  "uuid"                  uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "share_uuid"            uuid NOT NULL,

  -- NULL means the comment is about the collection as a whole rather than one of its members. An
  -- asset share always sets it.
  "asset_uuid"            uuid,

  -- Replies. One level deep is what commentThread.ts renders, and deeper nesting in a review
  -- thread produces a conversation nobody can follow in a 400px panel.
  "parent_uuid"           uuid,

  -- A comment may hang off a mark on the media instead of standing alone - "this logo" is only
  -- meaningful next to the box that says which logo.
  "share_annotation_uuid" uuid,

  "text"                  varchar NOT NULL,
  "author_name"           varchar NOT NULL,

  "created"               timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "edited"                timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),

  CONSTRAINT "share_comment_pkey" PRIMARY KEY ("uuid"),

  CONSTRAINT "share_comment_share_fkey"      FOREIGN KEY ("share_uuid")            REFERENCES "share"            ("uuid") ON DELETE CASCADE,
  CONSTRAINT "share_comment_asset_fkey"      FOREIGN KEY ("asset_uuid")            REFERENCES "asset"            ("uuid") ON DELETE CASCADE,
  CONSTRAINT "share_comment_parent_fkey"     FOREIGN KEY ("parent_uuid")           REFERENCES "share_comment"    ("uuid") ON DELETE CASCADE,
  CONSTRAINT "share_comment_annotation_fkey" FOREIGN KEY ("share_annotation_uuid") REFERENCES "share_annotation" ("uuid") ON DELETE CASCADE
);

CREATE TABLE "share_reaction" (
  "uuid"                  uuid NOT NULL DEFAULT uuid_generate_v4 (),
  "share_uuid"            uuid NOT NULL,

  -- Exactly one subject, as in `reaction`.
  "asset_uuid"            uuid,
  "share_comment_uuid"    uuid,
  "share_annotation_uuid" uuid,

  -- varchar + CHECK rather than reusing the ReactionType enum: that vocabulary belongs to the
  -- internal social features and gained a value (V2.78 RATING) for reasons that have nothing to do
  -- with a client saying yes or no to a cut. Keeping the two lists separate means neither can
  -- churn the other.
  "type"                  varchar NOT NULL,
  "author_name"           varchar NOT NULL,

  "created"               timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),

  CONSTRAINT "share_reaction_pkey" PRIMARY KEY ("uuid"),

  CONSTRAINT "share_reaction_type_check" CHECK ("type" IN ('APPROVE', 'REJECT', 'THUMBSUP', 'THUMBSDOWN', 'LOVE', 'QUESTION')),

  CONSTRAINT "share_reaction_subject_check" CHECK (
    num_nonnulls("asset_uuid", "share_comment_uuid", "share_annotation_uuid") = 1),

  CONSTRAINT "share_reaction_share_fkey"      FOREIGN KEY ("share_uuid")            REFERENCES "share"            ("uuid") ON DELETE CASCADE,
  CONSTRAINT "share_reaction_asset_fkey"      FOREIGN KEY ("asset_uuid")            REFERENCES "asset"            ("uuid") ON DELETE CASCADE,
  CONSTRAINT "share_reaction_comment_fkey"    FOREIGN KEY ("share_comment_uuid")    REFERENCES "share_comment"    ("uuid") ON DELETE CASCADE,
  CONSTRAINT "share_reaction_annotation_fkey" FOREIGN KEY ("share_annotation_uuid") REFERENCES "share_annotation" ("uuid") ON DELETE CASCADE
);

-- One reaction of a given type per link per subject - the analogue of reaction's
-- (creator_uuid, type, subject) key, with the share standing in for the creator. Three PARTIAL
-- unique indexes rather than one composite, because a composite over nullable columns never
-- conflicts under default NULL semantics and would enforce nothing at all. (V2.71 hit this and
-- reached for NULLS NOT DISTINCT; partial indexes do the same job without needing Postgres 15.)
CREATE UNIQUE INDEX "idx_share_reaction_asset_unique"
  ON "share_reaction" ("share_uuid", "type", "asset_uuid") WHERE "asset_uuid" IS NOT NULL;
CREATE UNIQUE INDEX "idx_share_reaction_comment_unique"
  ON "share_reaction" ("share_uuid", "type", "share_comment_uuid") WHERE "share_comment_uuid" IS NOT NULL;
CREATE UNIQUE INDEX "idx_share_reaction_annotation_unique"
  ON "share_reaction" ("share_uuid", "type", "share_annotation_uuid") WHERE "share_annotation_uuid" IS NOT NULL;

-- Postgres does not index the referencing side of a foreign key. Deleting a share, an asset, a
-- comment or an annotation cascades into these tables, and the owner's feedback panel reads
-- everything for one share at a time.
CREATE INDEX "idx_share_annotation_share" ON "share_annotation" ("share_uuid", "created");
CREATE INDEX "idx_share_annotation_asset" ON "share_annotation" ("asset_uuid");

CREATE INDEX "idx_share_comment_share"      ON "share_comment" ("share_uuid", "created");
CREATE INDEX "idx_share_comment_asset"      ON "share_comment" ("asset_uuid");
CREATE INDEX "idx_share_comment_parent"     ON "share_comment" ("parent_uuid");
CREATE INDEX "idx_share_comment_annotation" ON "share_comment" ("share_annotation_uuid");

CREATE INDEX "idx_share_reaction_share"      ON "share_reaction" ("share_uuid");
CREATE INDEX "idx_share_reaction_asset"      ON "share_reaction" ("asset_uuid");
CREATE INDEX "idx_share_reaction_comment"    ON "share_reaction" ("share_comment_uuid");
CREATE INDEX "idx_share_reaction_annotation" ON "share_reaction" ("share_annotation_uuid");

-- Tell the owner their client said something. The inbox, the addressed NOTIFICATION socket channel
-- and the sidebar bell all already exist (V2.70); this is one more value in the type vocabulary.
-- V2.70 chose a CHECK over an enum precisely so that adding one costs a DROP plus an ADD instead of
-- the DO block V2.55 needed.
--
-- The list is restated in full, which is the hazard of this pattern: the replacement must carry every
-- value added since V2.70, not only the ones that file listed. NODE_RUN_COMPLETED arrived in V2.83
-- and belongs here. Whoever adds the next value: read the CURRENT constraint, do not copy this block
-- from the migration that first created it.
ALTER TABLE "notification" DROP CONSTRAINT "notification_type_check";
ALTER TABLE "notification" ADD CONSTRAINT "notification_type_check" CHECK ("type" IN (
  'TASK_ASSIGNED', 'TASK_UNASSIGNED', 'TASK_STATUS_CHANGED',
  'TASK_COMMENT', 'COMMENT_REPLY', 'PIPELINE_RUN_FAILED', 'NODE_RUN_COMPLETED',
  'SHARE_FEEDBACK'));

COMMENT ON TABLE  "share_annotation" IS 'A mark a share visitor drew on the media - a timecode, a region, or both. Separate from `annotation` because that table requires a creator_uuid referencing a real user, and because its region columns are pixels where a responsive full-bleed viewer needs normalised coordinates';
COMMENT ON COLUMN "share_annotation"."time_from" IS 'Seconds as a float. The internal annotation table stores integer seconds, which cannot express a frame boundary - the precision a reviewer marking a cut is trying to convey';
COMMENT ON COLUMN "share_annotation"."area_x" IS 'Normalised 0..1 against the media dimensions, matching what ZoomableImage emits. Pixels would be meaningless across the viewport sizes this viewer runs at';
COMMENT ON COLUMN "share_annotation"."author_name" IS 'Denormalised from share.visitor_name at write time: what somebody was called when they wrote this is a historical fact and must not change retroactively';

COMMENT ON TABLE  "share_comment" IS 'A comment left through a share link by somebody with no Loom account. Kept apart from `comment` so outside opinion never merges with the internal record - which also matters before this text reaches the chat agent';
COMMENT ON COLUMN "share_comment"."asset_uuid" IS 'NULL means the comment is about the shared collection as a whole rather than one member';
COMMENT ON COLUMN "share_comment"."parent_uuid" IS 'One level of replies, matching what commentThread.ts renders';

COMMENT ON TABLE  "share_reaction" IS 'A share visitor reacting to an asset, a guest comment or a guest annotation. Its own type vocabulary rather than ReactionType, so neither list churns the other';
COMMENT ON COLUMN "share_reaction"."share_uuid" IS 'Stands in for creator_uuid in the uniqueness key: identity here is the link, not a person';
