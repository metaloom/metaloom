-- Show one asset or one collection to somebody who has no Loom account.
--
-- Until now the only way to let a client see material was to create them a user, grant it
-- permissions and teach them to sign in. For a one-off "look at these five clips and tell me which
-- one" that is absurd, so in practice the material left the system entirely - exported to a file
-- transfer service, reviewed there, and the feedback came back as an email nobody could attach to
-- the asset. This table is the alternative: a capability URL that opens a stripped-down viewer,
-- optionally behind a password, optionally with an expiry date.
--
-- THE ROW IS THE AUTHORITY. There is no session table and no share-side user. Everything the
-- visitor is allowed to do is decided by re-reading this row on every single request:
-- does it still exist, has it expired, does the requested asset actually belong to it, is
-- allow_download set. The session token the visitor carries proves only that they satisfied the
-- password once; it grants nothing by itself and it is not a JWT (see ShareAccessService for why
-- issuing one from the Loom signing key would have made a share token valid against every secured
-- route in the API).
--
-- ONE LINK IS ONE VISITOR. visitor_name is a column here rather than a row in a share_visitor
-- table, which is a deliberate simplification with a real cost: everyone holding a given link is
-- the same identity, so two people sharing one URL appear under one name and either may delete the
-- other's feedback. The product answer is "send each reviewer their own link", and the UI says so.
-- The alternative - a session table with one row per browser - buys per-person attribution at the
-- price of a table, a cleanup job and a second identity model, and nothing in the feedback surface
-- (V2.99) needs to distinguish two people behind one link badly enough to pay for it.
--
-- WHY NOT REUSE token. The `token` table (V2.1) is also a bearer capability, but it authenticates a
-- USER: a token resolves to a user_uuid and carries that user's permissions. A share resolves to
-- nothing and carries no permissions. Overloading `token` would have meant a token row whose
-- user_uuid is null and whose permission set must never be consulted - a special case in the
-- middle of the authentication path, which is the last place to put one.

CREATE TABLE "share" (
  "uuid"              uuid NOT NULL DEFAULT uuid_generate_v4 (),

  -- The public half of the capability. 128 bits of SecureRandom, base64url-encoded to 22 chars.
  -- NOT the uuid: a uuid in a URL invites someone to try it against /api/v1/assets/<same uuid>,
  -- and v4 uuids are for identifying rows, not for withstanding being guessed at.
  --
  -- The alphabet matters beyond entropy. UIService serves the SPA for any /ui/* path whose last
  -- segment has no file extension and falls through to the static handler otherwise, so a slug
  -- containing a dot would 404 instead of opening. base64url is [A-Za-z0-9_-] and has no dot.
  "slug"              varchar NOT NULL,

  -- What is being shared. The discriminator is redundant with which FK is set, and is here anyway
  -- so a reader does not have to infer the kind from a null check - the same call V2.83 made for
  -- pipeline_run.kind. The CHECK below keeps the two halves from disagreeing.
  "target_type"       varchar NOT NULL,
  "asset_uuid"        uuid,
  "collection_uuid"   uuid,

  -- bcrypt, same encoder and cost as user.password_hash (AuthenticationServiceImpl). NULL means the
  -- link is open - a distinct state from "the empty password", which is why this is nullable rather
  -- than defaulted.
  "password_hash"     varchar,

  -- NULL means never. A share that has expired is answered 404, not 403: an expired link and a
  -- link that never existed must be indistinguishable, or the endpoint becomes an oracle for
  -- whether a given slug was ever real.
  "expires_at"        timestamp WITHOUT TIME ZONE,

  -- What the visitor may do. Per-share rather than instance-wide because the answer genuinely
  -- differs per link: a rough cut goes out watermarked and unreviewable, a delivery goes out
  -- downloadable, a client review goes out commentable.
  "allow_download"    boolean NOT NULL DEFAULT true,
  "show_metadata"     boolean NOT NULL DEFAULT true,
  "allow_comments"    boolean NOT NULL DEFAULT false,
  "allow_reactions"   boolean NOT NULL DEFAULT false,
  "allow_annotations" boolean NOT NULL DEFAULT false,

  -- Who is looking, as they chose to identify themselves on the first visit. Set once and never
  -- overwritten, so the second visitor does not silently rename the first one's feedback. NULL
  -- until somebody opens the link; the UI offers a Skip button which stores the localised
  -- equivalent of "Anonymous" rather than leaving this null, so that "nobody has opened it" stays
  -- distinguishable from "somebody opened it and declined to say who".
  "visitor_name"      varchar,
  "first_visited_at"  timestamp WITHOUT TIME ZONE,

  -- Enough for the owner's share list to say "opened 3 times, last on Tuesday". Written once per
  -- redeemed session, not per request, so scrubbing a video does not inflate the count.
  "last_viewed_at"    timestamp WITHOUT TIME ZONE,
  "view_count"        integer NOT NULL DEFAULT 0,

  -- Named "meta" deliberately: the jOOQ forcedTypes include-expression in loom/db/jooq/pom.xml
  -- matches `.*\.meta.*`, so this column picks up JsonObjectConverter with no pom edit.
  "meta"              jsonb,

  "created"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  -- NULLABLE, and this one is a requirement rather than an accommodation for machine writes:
  -- deleting a user must not delete their shares. A link that was handed to a client keeps working
  -- when the editor who made it leaves, and the owner column simply goes empty. Hence SET NULL
  -- below rather than the CASCADE every other creator_uuid in this schema uses.
  "creator_uuid"      uuid,
  "edited"            timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "editor_uuid"       uuid,

  CONSTRAINT "share_pkey" PRIMARY KEY ("uuid"),

  -- varchar + CHECK rather than a Postgres enum, per the V2.55 lesson: removing one enum value cost
  -- a rename, a rebuild from pg_enum and three column re-types inside a DO block. This list will
  -- grow (a library or a person is a plausible fourth and fifth target) and a CHECK is DROP + ADD.
  CONSTRAINT "share_target_type_check" CHECK ("target_type" IN ('ASSET', 'COLLECTION')),

  -- Exactly one target. num_nonnulls is the same device V2.69 used for task_assignee.
  CONSTRAINT "share_target_one_check" CHECK (num_nonnulls("asset_uuid", "collection_uuid") = 1),

  -- ...and it must be the one target_type names. Without this the discriminator could drift away
  -- from the FK and a reader trusting either one would be wrong half the time.
  CONSTRAINT "share_target_agrees_check" CHECK (
    ("target_type" = 'ASSET'      AND "asset_uuid"      IS NOT NULL) OR
    ("target_type" = 'COLLECTION' AND "collection_uuid" IS NOT NULL)),

  -- The slug is the credential. A duplicate would hand two shares the same URL.
  CONSTRAINT "share_slug_key" UNIQUE ("slug"),

  -- The target CASCADEs: a link to an asset that no longer exists can only ever render an error,
  -- and leaving the row would keep a dead URL answering 200 with an empty page.
  CONSTRAINT "share_asset_fkey"      FOREIGN KEY ("asset_uuid")      REFERENCES "asset"      ("uuid") ON DELETE CASCADE,
  CONSTRAINT "share_collection_fkey" FOREIGN KEY ("collection_uuid") REFERENCES "collection" ("uuid") ON DELETE CASCADE,

  -- The people SET NULL. See creator_uuid above - this is the stated requirement, not an oversight.
  CONSTRAINT "share_creator_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid") ON DELETE SET NULL,
  CONSTRAINT "share_editor_fkey"  FOREIGN KEY ("editor_uuid")  REFERENCES "user" ("uuid") ON DELETE SET NULL
);

-- The hot path: every single request a visitor makes resolves the slug first. UNIQUE already
-- creates this index; it is named here only so the constraint above reads as the credential lookup
-- it is.

-- Postgres does not index the referencing side of a foreign key. Without these, deleting an asset,
-- a collection or a user seq-scans this table hunting for cascade victims.
CREATE INDEX "idx_share_asset"      ON "share" ("asset_uuid");
CREATE INDEX "idx_share_collection" ON "share" ("collection_uuid");
CREATE INDEX "idx_share_creator"    ON "share" ("creator_uuid");

-- "Which of my links are about to lapse" and any future sweep of dead links. PARTIAL, because a
-- share that never expires is the common case and has nothing to scan for.
CREATE INDEX "idx_share_expires_at" ON "share" ("expires_at") WHERE "expires_at" IS NOT NULL;

COMMENT ON TABLE  "share" IS 'One shareable link to one asset or collection, viewable without a Loom account. The row is the authority: expiry, password and per-visitor capabilities are re-read on every request rather than baked into the token the visitor carries';
COMMENT ON COLUMN "share"."slug" IS 'The public half of the capability - 128 random bits, base64url. Not the uuid, and never containing a dot: UIService would route a dotted path to the static handler instead of the app';
COMMENT ON COLUMN "share"."target_type" IS 'ASSET or COLLECTION. Redundant with which FK is set, and kept so readers do not infer the kind from a null check. varchar + CHECK because this list will grow';
COMMENT ON COLUMN "share"."password_hash" IS 'bcrypt, same encoder as user.password_hash. NULL means the link is open, which is a different state from an empty password';
COMMENT ON COLUMN "share"."expires_at" IS 'NULL means never. An expired share answers 404, not 403, so a lapsed link is indistinguishable from one that never existed';
COMMENT ON COLUMN "share"."visitor_name" IS 'How the first visitor identified themselves. Set once and never overwritten. NULL means nobody has opened the link yet - a visitor who skipped the question is stored as the localised "Anonymous", not as NULL';
COMMENT ON COLUMN "share"."creator_uuid" IS 'Nullable ON DELETE SET NULL by requirement: deleting a user must not delete their shares, so a link handed to a client outlives the editor who made it';
COMMENT ON COLUMN "share"."view_count" IS 'Incremented once per redeemed session, not per request, so scrubbing a video does not inflate it';
COMMENT ON COLUMN "share"."allow_comments" IS 'Guest feedback lives in share_comment / share_annotation / share_reaction (V2.99). These three toggles ship in this migration so the share row has one complete shape rather than growing columns a migration later';
