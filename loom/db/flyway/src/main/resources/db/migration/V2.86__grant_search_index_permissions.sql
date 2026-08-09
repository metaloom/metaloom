-- Give the admin role the two permissions V2.85 added.
--
-- This is not redundant with DatabaseInitializer. That loops over Permission.values() and grants the
-- lot, but only inside `if (role == null)` — that is, only when it creates the admin role for the
-- first time. On an installation that already has one, a new enum value is therefore granted to
-- nobody, and the administrator of an upgraded instance would find the new admin screen answering
-- 403 with no way to fix it short of hand-editing the table. Fresh installs are unaffected either
-- way; this file exists for the upgrade path.
--
-- Separate from V2.85 because Postgres will not let a transaction use an enum value that the same
-- transaction added, and Flyway wraps each migration in one transaction.
--
-- Written against the role by name, matching DatabaseInitializer.ROLE_NAME. An instance that renamed
-- or removed its admin role gets no rows and no error, which is the right outcome: this is a
-- convenience for the default setup, not a policy that should reinstate a grant an operator removed.
INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'READ_SEARCH_INDEX'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;

INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'MANAGE_SEARCH_INDEX'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;
