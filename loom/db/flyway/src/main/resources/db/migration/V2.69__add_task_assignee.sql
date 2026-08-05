-- Give a task a human owner.
--
-- Until now "assigning" a task meant linking it to an ASSET (asset_task, V2.8) or an
-- ANNOTATION (annotation_task, V2.16) - never to a person. The task table carries only
-- creator_uuid and editor_uuid, which are the generic audit columns every entity has and
-- say who typed, not who is responsible. A workflow item nobody is answerable for is a
-- note, so this is the column that makes `task` a task.
--
-- ONE table with a CHECK rather than a task_user_assignee / task_group_assignee pair,
-- because the query this exists to serve - "everything assigned to me, whether directly
-- or through a group I am in" - is then a single join instead of a UNION over two tables:
--
--   SELECT t.* FROM task t
--     JOIN task_assignee ta ON ta.task_uuid = t.uuid
--     LEFT JOIN user_group ug ON ug.group_uuid = ta.group_uuid
--    WHERE ta.user_uuid = ? OR ug.user_uuid = ?
--
-- Groups are the ACL groups from V2.1. Assigning to one deliberately does NOT snapshot its
-- membership here: the assignment says "this team owns it" and the roster is resolved when
-- read. (Notification delivery makes the opposite choice and materialises per recipient at
-- dispatch time - see V2.68. The two are not in conflict: ownership is a live fact, whereas
-- "you were told" is a historical one that must not change when the roster does.)

CREATE TABLE "task_assignee" (
  "task_uuid"     uuid NOT NULL,
  "user_uuid"     uuid,
  "group_uuid"    uuid,
  "assigned"      timestamp WITHOUT TIME ZONE NOT NULL DEFAULT (now()),
  "assigner_uuid" uuid,

  -- Exactly one target. A row with both set would be ambiguous; a row with neither would be
  -- an assignment to nobody, which is what deleting the row means.
  CONSTRAINT "task_assignee_exactly_one_target"
    CHECK (num_nonnulls("user_uuid", "group_uuid") = 1),

  -- Deliberately CASCADE on all three, unlike asset_task - whose asset FK still BLOCKS an
  -- asset delete, a behaviour AssetCascadeTest pins on purpose. An assignment is not a
  -- record worth protecting a delete for: an assignment to a deleted user, group or task
  -- cannot be acted on, listed or revoked, so keeping it only strands a row.
  CONSTRAINT "task_assignee_task_fkey"  FOREIGN KEY ("task_uuid")  REFERENCES "task"  ("uuid") ON DELETE CASCADE,
  CONSTRAINT "task_assignee_user_fkey"  FOREIGN KEY ("user_uuid")  REFERENCES "user"  ("uuid") ON DELETE CASCADE,
  CONSTRAINT "task_assignee_group_fkey" FOREIGN KEY ("group_uuid") REFERENCES "group" ("uuid") ON DELETE CASCADE,

  -- SET NULL, not CASCADE: who assigned is provenance ABOUT the assignment, not the
  -- assignment itself. Losing an assignment because the person who made it left the
  -- company would silently unassign live work.
  CONSTRAINT "task_assignee_assigner_fkey" FOREIGN KEY ("assigner_uuid") REFERENCES "user" ("uuid") ON DELETE SET NULL
);

-- No PRIMARY KEY: a PK cannot contain nullable columns, and one of the two target columns is
-- always null. Uniqueness is therefore two PARTIAL unique indexes, which also give assignment
-- its idempotency (ON CONFLICT DO NOTHING) - assigning twice is not an error, it is a no-op.
CREATE UNIQUE INDEX "task_assignee_user_key"  ON "task_assignee" ("task_uuid", "user_uuid")  WHERE "user_uuid"  IS NOT NULL;
CREATE UNIQUE INDEX "task_assignee_group_key" ON "task_assignee" ("task_uuid", "group_uuid") WHERE "group_uuid" IS NOT NULL;

-- Postgres does not index the referencing side of a foreign key. Without these, every user
-- and group delete seq-scans task_assignee looking for cascade victims, and the "assigned to
-- me" listing has no index to stand on.
CREATE INDEX "idx_task_assignee_user"  ON "task_assignee" ("user_uuid");
CREATE INDEX "idx_task_assignee_group" ON "task_assignee" ("group_uuid");

COMMENT ON TABLE "task_assignee" IS 'Who is responsible for a task: a user or a group, one target per row, several rows per task. Written by explicit insert/delete on TaskDaoImpl like asset_task - it has no uuid and therefore no DAO of its own, and jOOQ generates a TableRecord rather than an UpdatableRecord for it';
COMMENT ON COLUMN "task_assignee"."user_uuid" IS 'The assigned user. Null when this row assigns to a group instead';
COMMENT ON COLUMN "task_assignee"."group_uuid" IS 'The assigned ACL group. Membership is resolved on read, not snapshotted here, so adding someone to the group hands them the task';
COMMENT ON COLUMN "task_assignee"."assigned" IS 'When the assignment was made';
COMMENT ON COLUMN "task_assignee"."assigner_uuid" IS 'Who made the assignment. Nullable: the assigner may since have been deleted, and a machine-made assignment has none. This is what lets a notification say who assigned you, and what self-notification suppression tests against';
