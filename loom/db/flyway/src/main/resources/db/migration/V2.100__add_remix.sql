-- Remix: a named group holding an original asset and everything derived from it
-- (spec/tasks/ASSET_REMIX_PLAN.md).
--
-- This replaces "asset_remix" from V2.8, which was an undirected, untyped pair table
-- (asset_a_uuid, asset_b_uuid) with no primary key, no unique constraint and no foreign key on
-- editor_uuid. In the eighteen months since V2.8 nothing ever wrote a row to it: outside generated
-- jOOQ code the only references in the repository were the integrity checks that had to special-case
-- its missing uuid. The drop is therefore lossless on every installation.
--
-- The pair model was replaced rather than repaired because the feature it carries is a *group*: a
-- named thing a user opens like a folder, holding one original plus the cuts, re-encodes and edits
-- made from it. A pair table cannot express a group, a name, or which of the two assets is the
-- source. Directed typed edges (source, derived, relation) were considered and rejected for the
-- human-curated case: they answer "where did this come from" but not "show me this set", and the UI
-- has to render a set. Typed machine lineage - a node recording that it produced asset B from asset
-- A - remains unmodelled and is still open as G3 in spec/concept/ASSET_METADATA_WRITE.md.
--
-- Shape follows dedup_group / dedup_group_member (V2.61), which solves the same problem: one row per
-- group, one row per membership, a role column distinguishing the special member, and a
-- denormalised pointer on the group for convenience.
--
--   source_asset_uuid  is denormalised for convenience; the authoritative source is the member with
--                      role='SOURCE'. The DAO keeps the two consistent inside one transaction.
--   ON DELETE SET NULL on source_asset_uuid but ON DELETE CASCADE on the member: deleting the
--                      original asset must not silently delete the whole remix along with the
--                      derived assets that are still there, but a member row without its asset is
--                      meaningless. Same split, and the same reasoning, as dedup_group.keep_asset_uuid.
--   role               is varchar + CHECK rather than a Postgres enum, so the vocabulary can grow
--                      without an ALTER TYPE migration. Same choice as dedup_group_member.role.
--   ordinal            is the user's ordering inside the remix; NULL sorts last.
--   A remix holds assets, never other remixes. Nesting is deliberately not modelled.

DROP TABLE "asset_remix";

CREATE TABLE "remix" (
    "uuid"              uuid NOT NULL DEFAULT uuid_generate_v4(),
    "name"              varchar NOT NULL,
    "description"       varchar,
    "source_asset_uuid" uuid,
    "meta"              jsonb,

    "created"           timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid"      uuid,
    "edited"            timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"       uuid,

    CONSTRAINT "remix_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "remix_source_asset_uuid_fkey" FOREIGN KEY ("source_asset_uuid") REFERENCES "asset" ("uuid") ON DELETE SET NULL,
    CONSTRAINT "remix_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "remix_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid")
);

CREATE TABLE "remix_member" (
    "uuid"         uuid NOT NULL DEFAULT uuid_generate_v4(),
    "remix_uuid"   uuid NOT NULL,
    "asset_uuid"   uuid NOT NULL,
    "role"         varchar NOT NULL DEFAULT 'DERIVED',
    "ordinal"      int,

    "created"      timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "creator_uuid" uuid,
    "edited"       timestamp WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    "editor_uuid"  uuid,

    CONSTRAINT "remix_member_pkey" PRIMARY KEY ("uuid"),
    CONSTRAINT "remix_member_remix_fkey" FOREIGN KEY ("remix_uuid") REFERENCES "remix" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "remix_member_asset_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
    CONSTRAINT "remix_member_creator_uuid_fkey" FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "remix_member_editor_uuid_fkey" FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid"),
    CONSTRAINT "remix_member_role_check" CHECK ("role" IN ('SOURCE', 'DERIVED')),
    CONSTRAINT "remix_member_unique" UNIQUE ("remix_uuid", "asset_uuid")
);

-- Postgres does not index the referencing side of a foreign key, and every one of these is a lookup
-- direction the application actually uses: members of a remix, remixes an asset belongs to, and the
-- cascade that runs when an asset is deleted.
CREATE INDEX "idx_remix_source_asset" ON "remix" ("source_asset_uuid");
CREATE INDEX "idx_remix_member_remix" ON "remix_member" ("remix_uuid");
CREATE INDEX "idx_remix_member_asset" ON "remix_member" ("asset_uuid");

-- "At most one SOURCE per remix" is a real invariant, so the database enforces it rather than
-- trusting every write path to remember. A partial unique index is the only way to say it: a plain
-- UNIQUE (remix_uuid, role) would also forbid a second DERIVED member. Same technique as V2.99.
CREATE UNIQUE INDEX "remix_member_single_source" ON "remix_member" ("remix_uuid") WHERE "role" = 'SOURCE';

COMMENT ON TABLE "remix" IS 'A named group of assets that are versions of one another - an original plus the cuts, re-encodes and edits made from it. Replaces the never-written asset_remix pair table from V2.8.';
COMMENT ON COLUMN "remix"."name" IS 'User-facing label shown on the remix card in the asset browser.';
COMMENT ON COLUMN "remix"."source_asset_uuid" IS 'Denormalised pointer to the SOURCE member; the authoritative source is the remix_member row with role=SOURCE. Nulled rather than cascading when the original asset is deleted, so the remaining derived assets keep their group.';
COMMENT ON COLUMN "remix"."meta" IS 'Custom meta properties to the element';
COMMENT ON COLUMN "remix"."creator_uuid" IS 'NULL when created by a machine rather than a user.';
COMMENT ON TABLE "remix_member" IS 'Membership of one asset in one remix. Unique per (remix, asset); cascades away with either side.';
COMMENT ON COLUMN "remix_member"."role" IS 'SOURCE (the original this remix is built around, at most one per remix) or DERIVED (anything made from it).';
COMMENT ON COLUMN "remix_member"."ordinal" IS 'User-defined ordering within the remix. NULL sorts last.';
