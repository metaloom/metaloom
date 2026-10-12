-- V2.6 created vector_config with no primary key, no unique constraint on "uuid" and no foreign
-- keys at all, while declaring creator_uuid and editor_uuid NOT NULL. jOOQ generated a
-- TableRecordImpl for it (nothing to update or delete by), so no DAO could be written against the
-- table, and the integrity report had to hunt for the consequences at runtime
-- (DUPLICATE_VECTOR_CONFIG_UUID, DANGLING_VECTOR_CONFIG_ACTOR).
--
-- token has the same omission on one column: V2.1 declared the creator_uuid FK and forgot
-- editor_uuid, which is what DANGLING_TOKEN_EDITOR reports.
--
-- This adds the key and the three foreign keys the schema always implied. The integrity checks stay:
-- they still describe what an installation may hold if it ran without these constraints.
--
-- Repair is a no-op. vector_config had no writer before this release - nothing in the code inserted
-- into it - so the table is empty everywhere. token.editor_uuid is written by the token DAO from the
-- authenticated user, so it names a real user on every installation that has not been edited by hand.
-- If an installation was, this migration fails on the FK, and the integrity report names the row.
--
-- No ON DELETE action, matching detection_creator_uuid_fkey (V2.43): users are soft-deleted, and
-- silently losing who wrote a row is worse than refusing a hard delete.

ALTER TABLE "vector_config" ALTER COLUMN "uuid" SET NOT NULL;
ALTER TABLE "vector_config" ADD CONSTRAINT "vector_config_pkey" PRIMARY KEY ("uuid");

ALTER TABLE "vector_config" ADD CONSTRAINT "vector_config_creator_uuid_fkey"
  FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "vector_config" ADD CONSTRAINT "vector_config_editor_uuid_fkey"
  FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");

ALTER TABLE "token" ADD CONSTRAINT "token_editor_uuid_fkey"
  FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");
