-- Reading, triaging and deleting the problem reports users submit from the UI.
--
-- THERE IS DELIBERATELY NO `CREATE_FAILURE_REPORT`. Submitting one is not a privileged act: the row
-- describes a failure that happened to the person writing it, in their own session, and the route
-- sits behind `secure()` so the caller is always an authenticated user. A permission here would be
-- a permission to *complain*, and its practical effect on every upgraded installation - which is
-- exactly the case the V2.98 note about DatabaseInitializer exists for - would be that the one
-- action the product offers when something breaks answers 403. That is the opposite of the point.
-- The missing CREATE_NOTIFICATION constant is the same shape for a different reason: that one has
-- no route at all, this one has a route that must stay open.
--
-- The other three are real authorities. A report carries the reporter's own words and, when they
-- chose to attach one, a screenshot of whatever was on their screen - which may show assets that
-- the reader of the inbox is not otherwise cleared to see. Reading the inbox is therefore its own
-- grant, not something READ_ASSET implies.
--
-- Enum additions live in their own migration on purpose: ALTER TYPE ... ADD VALUE cannot run inside
-- a transaction block on older Postgres, and a value added in one transaction is not usable in it.
-- The tables are V2.107 and the grant that names these is V2.108. Nothing else may go in this file.
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'READ_FAILURE_REPORT';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'UPDATE_FAILURE_REPORT';
ALTER TYPE loom_permission ADD VALUE IF NOT EXISTS 'DELETE_FAILURE_REPORT';
