-- Give the admin role the permissions V2.101 added.
--
-- Not redundant with DatabaseInitializer: that loops over Permission.values() and grants the lot,
-- but only inside `if (role == null)` - that is, only when it creates the admin role for the first
-- time. On an installation that already has one, a new enum value is granted to nobody, and the
-- administrator of an upgraded instance would find the remix actions answering 403 with no way to
-- fix it short of hand-editing the table. Fresh installs are unaffected either way; this file exists
-- for the upgrade path. Same reasoning and same shape as V2.86, V2.95 and V2.98.
--
-- Separate from V2.101 because Postgres will not let a transaction use an enum value that the same
-- transaction added, and Flyway wraps each migration in one transaction.
--
-- Written against the role by name, matching DatabaseInitializer.ROLE_NAME. An instance that renamed
-- or removed its admin role gets no rows and no error, which is the right outcome: this is a
-- convenience for the default setup, not a policy that should reinstate a grant an operator
-- deliberately removed.
INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'CREATE_REMIX'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;

INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'READ_REMIX'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;

INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'UPDATE_REMIX'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;

INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'DELETE_REMIX'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;
