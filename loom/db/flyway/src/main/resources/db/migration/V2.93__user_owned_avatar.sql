-- ---------------------------------------------------------------------------
-- User-owned avatar
--
-- A fifth nullable target column on attachment, alongside asset_uuid (V2.44), embedding_uuid
-- (V2.43), detection_uuid (V2.79) and person_uuid (V2.90). Like those it carries no CHECK pairing it
-- against the others: the targets are not alternatives, and V2.44 already argued that case.
--
-- The lifetime argument is V2.90's, applied to an account instead of a subject. A user's picture is
-- not derived from anything - it was uploaded to the account. Nothing but deleting the account may
-- delete it, so no asset or detection cascade can reach it.
-- ---------------------------------------------------------------------------
ALTER TABLE "attachment" ADD COLUMN "user_uuid" uuid;
ALTER TABLE "attachment"
    ADD CONSTRAINT "attachment_user_uuid_fkey" FOREIGN KEY ("user_uuid") REFERENCES "user" ("uuid") ON DELETE CASCADE;

CREATE INDEX "idx_attachment_user_uuid" ON "attachment" ("user_uuid");

-- ---------------------------------------------------------------------------
-- One picture, not a gallery
--
-- This is where a user diverges from a person. A person has many images because a person is a
-- subject that face detection keeps finding in new material, and the avatar is a designation among
-- them (V2.90 deliberately declined a unique key for exactly that reason). A user is an account.
-- Nobody needs a gallery of account pictures, and making "at most one" a schema fact rather than a
-- convention removes the whole designate-which-one concept from the REST layer: an upload replaces.
--
-- Partial rather than plain, because user_uuid is on the shared attachment table and a future
-- user-owned type must not be forced into the same cardinality.
--
-- It cannot live in V2.92 next to the enum value it names: Postgres refuses to use an enum value
-- that the same transaction added, and Flyway wraps each migration in one transaction.
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX "idx_attachment_user_avatar_unique"
    ON "attachment" ("user_uuid") WHERE "type" = 'USER_AVATAR';

-- ---------------------------------------------------------------------------
-- The pointer
--
-- Denormalised on purpose, mirroring person.avatar_attachment_uuid (V2.90): every screen that
-- renders a username also renders the picture, and a partial-index lookup on every such read is a
-- join the model builder would have to do by hand.
--
-- SET NULL rather than CASCADE, in the one direction that could be confused: deleting the picture
-- must not delete the account. The reverse - deleting the account - is covered by the CASCADE above.
--
-- The two foreign keys form a cycle ("user" -> attachment -> "user"). Both columns are nullable, so
-- writes order themselves: insert the image, then point the user at it.
--
-- Note that UserEndpointService.delete soft-deletes (markDeleted()) rather than issuing a DELETE, so
-- the CASCADE is a safety net for a hard delete, not the working path. A disabled account keeps its
-- picture, and those bytes keep being counted by the storage report. That is the honest answer:
-- the row is still there.
-- ---------------------------------------------------------------------------
ALTER TABLE "user" ADD COLUMN "avatar_attachment_uuid" uuid;
ALTER TABLE "user"
    ADD CONSTRAINT "user_avatar_attachment_uuid_fkey" FOREIGN KEY ("avatar_attachment_uuid") REFERENCES "attachment" ("uuid") ON DELETE SET NULL;

CREATE INDEX "idx_user_avatar_attachment_uuid" ON "user" ("avatar_attachment_uuid");

-- ---------------------------------------------------------------------------
-- Documentation
-- ---------------------------------------------------------------------------
COMMENT ON COLUMN "attachment"."user_uuid" IS 'User account that owns this picture. Independent of any asset: an account picture is uploaded to the account, never derived from material.';
COMMENT ON COLUMN "user"."avatar_attachment_uuid" IS 'The account''s avatar picture. At most one exists per user, enforced by idx_attachment_user_avatar_unique.';
