-- role_permission carried two disagreeing constraints since V2.1:
--
--     PRIMARY KEY ("role_uuid", "permission")
--     CREATE UNIQUE INDEX ON ("role_uuid", "resource", "permission")
--
-- The primary key is the narrower of the two, so the "resource" column could never do what
-- the unique index suggests: a role can hold a given permission exactly once, full stop.
-- The index is pure dead weight - every triple it would reject is already rejected by the
-- primary key.
--
-- The column is dropped rather than the key widened, because "resource" is not read by
-- anything that makes an authorization decision. LoomAuthorizationProvider turns the loaded
-- set into PermissionBasedAuthorization.create(perm.getPermission()) and never looks at
-- ResourcePermission.getResource(). Permissions in Loom are global: READ_ASSET means "read
-- every asset". Keeping a column that looks like a scope but scopes nothing is worse than
-- not having it - it invites grants that silently confer more authority than they appear to.
--
-- Dropping it cannot create duplicates: the primary key (role_uuid, permission) already
-- guarantees at most one row per pair, which is exactly the grain that survives.
--
-- Scope note: user_permission and token_permission have their own, worse variant of this
-- defect (a single-column primary key, so at most ONE grant row per user / per token). They
-- are deliberately left alone here - fixing them changes the semantics of direct user grants
-- and belongs in its own change.

DROP INDEX IF EXISTS "role_permission_role_uuid_resource_permission_idx";

ALTER TABLE "role_permission" DROP COLUMN IF EXISTS "resource";

COMMENT ON TABLE "role_permission" IS
'Permissions granted by a role. The grain is (role_uuid, permission) - grants are global, not
scoped to an object. A user receives these through group membership: user -> user_group ->
group -> role_group -> role. Rows cascade when the role is deleted.';

COMMENT ON COLUMN "role_permission"."permission" IS 'Permission granted by the role';
