-- ---------------------------------------------------------------------------
-- Person-owned images
--
-- A fourth target column on attachment, alongside asset_uuid (V2.44), embedding_uuid (V2.43) and
-- detection_uuid (V2.79). Like those it is nullable and, like those, it carries no CHECK pairing it
-- against the others: the targets are not alternatives, and V2.44 already argued that case.
--
-- What is different is the direction of the cascade that matters. A person image is not derived from
-- anything - it was uploaded to the person, or copied from a face crop into the person's own keeping.
-- Nothing but deleting the person may delete it.
-- ---------------------------------------------------------------------------
ALTER TABLE "attachment" ADD COLUMN "person_uuid" uuid;
ALTER TABLE "attachment"
    ADD CONSTRAINT "attachment_person_uuid_fkey" FOREIGN KEY ("person_uuid") REFERENCES "person" ("uuid") ON DELETE CASCADE;

CREATE INDEX "idx_attachment_person_uuid" ON "attachment" ("person_uuid");

-- No unique key over (person_uuid, type, node_kind, variant), unlike the asset and detection
-- idempotency indexes above it. Those exist because a producer re-running over the same input must
-- overwrite its previous output rather than accumulate copies. A person's gallery is the opposite:
-- every upload is a distinct picture a human chose to add, and two of them may legitimately be
-- byte-identical.

-- ---------------------------------------------------------------------------
-- The avatar
--
-- SET NULL rather than CASCADE, in the one direction that could be confused: deleting the picture a
-- person happens to be shown by must not delete the person. The reverse - deleting the person - is
-- covered by the CASCADE above, which takes every image including this one.
--
-- The two foreign keys form a cycle (person -> attachment -> person). Both columns are nullable, so
-- writes order themselves: insert the image, then point the person at it.
-- ---------------------------------------------------------------------------
ALTER TABLE "person" ADD COLUMN "avatar_attachment_uuid" uuid;
ALTER TABLE "person"
    ADD CONSTRAINT "person_avatar_attachment_uuid_fkey" FOREIGN KEY ("avatar_attachment_uuid") REFERENCES "attachment" ("uuid") ON DELETE SET NULL;

CREATE INDEX "idx_person_avatar_attachment_uuid" ON "person" ("avatar_attachment_uuid");

-- ---------------------------------------------------------------------------
-- Documentation
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN "attachment"."person_uuid" IS 'Person that owns this image. Independent of any asset: a person image outlives the material it may have come from.';
COMMENT ON COLUMN "person"."avatar_attachment_uuid" IS 'The person image shown as this person''s avatar. One of the person''s own images, never a pointer into an asset.';
