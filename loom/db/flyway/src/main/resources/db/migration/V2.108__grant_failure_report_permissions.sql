-- Give the admin role the permissions V2.106 added.
--
-- Not redundant with DatabaseInitializer: that loops over Permission.values() and grants the lot,
-- but only inside `if (role == null)` - that is, only when it creates the admin role for the first
-- time. On an installation that already has one, a new enum value is granted to nobody, and the
-- administrator of an upgraded instance would find the problem-report inbox answering 403 with no
-- way to fix it short of hand-editing the table. Same reasoning and same shape as V2.98.
--
-- Note what is NOT granted here: there is no CREATE_FAILURE_REPORT to grant. Submitting a report
-- needs authentication and nothing else - see the reasoning in V2.106 - so ordinary users can
-- report a failure on an upgraded instance the moment it deploys, without an operator granting
-- anything first. That is the property that makes the feature work at all.
--
-- Written against the role by name, matching DatabaseInitializer.ROLE_NAME. An instance that
-- renamed or removed its admin role gets no rows and no error, which is the right outcome: this is
-- a convenience for the default setup, not a policy that should reinstate a grant an operator
-- deliberately removed.
INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'READ_FAILURE_REPORT'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;

INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'UPDATE_FAILURE_REPORT'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;

INSERT INTO "role_permission" ("role_uuid", "permission")
SELECT "uuid", 'DELETE_FAILURE_REPORT'::loom_permission FROM "role" WHERE "name" = 'admin-role'
ON CONFLICT DO NOTHING;
