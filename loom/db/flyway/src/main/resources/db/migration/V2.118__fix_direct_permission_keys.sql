-- user_permission and token_permission have carried a single-column primary key since V2.1:
--
--     PRIMARY KEY ("user_uuid")        -- resp. ("token_uuid")
--     CREATE UNIQUE INDEX ON (<subject>, "resource", "permission")
--
-- The primary key is the narrower of the two, so a user or a token could hold exactly ONE direct
-- grant, ever: a second INSERT was a primary key violation, not an added permission. The unique
-- index could never fire. The test suite worked around it by granting through group + role, which
-- is how the defect stayed invisible.
--
-- This applies what V2.64 decided for role_permission to the two remaining grant tables: the grain
-- becomes (subject, permission) and the "resource" column goes. Nothing that makes an
-- authorization decision reads it - LoomAuthorizationProvider only ever looks at the permission -
-- and permissions in Loom are global: READ_ASSET means "read every asset". A column that looks like
-- a scope but scopes nothing invites grants that confer more authority than they appear to.
--
-- No backfill and no duplicate guard: the old key allowed at most one row per subject, so the
-- wider key cannot collide.
--
-- token_permission's FK to token also gains ON DELETE CASCADE, matching user_permission. Without it
-- a token holding a grant could not be deleted at all.

-- The V2.1 unique indexes were created without a name; Postgres derived these. Dropping the column
-- below would remove them anyway - this states it rather than relying on the side effect.
DROP INDEX IF EXISTS "user_permission_user_uuid_resource_permission_idx";
DROP INDEX IF EXISTS "token_permission_token_uuid_resource_permission_idx";

ALTER TABLE "user_permission" DROP CONSTRAINT "user_permission_pkey";
ALTER TABLE "user_permission" DROP COLUMN IF EXISTS "resource";
ALTER TABLE "user_permission" ADD CONSTRAINT "user_permission_pkey" PRIMARY KEY ("user_uuid", "permission");

ALTER TABLE "token_permission" DROP CONSTRAINT "token_permission_pkey";
ALTER TABLE "token_permission" DROP COLUMN IF EXISTS "resource";
ALTER TABLE "token_permission" ADD CONSTRAINT "token_permission_pkey" PRIMARY KEY ("token_uuid", "permission");

ALTER TABLE "token_permission" DROP CONSTRAINT IF EXISTS "token_permission_token_uuid_fkey";
ALTER TABLE "token_permission" ADD CONSTRAINT "token_permission_token_uuid_fkey"
  FOREIGN KEY ("token_uuid") REFERENCES "token" ("uuid") ON DELETE CASCADE;

COMMENT ON TABLE "user_permission" IS
'Permissions granted directly to a user, beside those reaching them through group membership. The
grain is (user_uuid, permission) - grants are global, not scoped to an object. Rows cascade when the
user is deleted.';

COMMENT ON TABLE "token_permission" IS
'Permissions granted to an API token. The grain is (token_uuid, permission) - grants are global, not
scoped to an object. Rows cascade when the token is deleted.';

COMMENT ON COLUMN "user_permission"."permission" IS 'Permission granted to the user';
COMMENT ON COLUMN "token_permission"."permission" IS 'Permission granted to the token';
