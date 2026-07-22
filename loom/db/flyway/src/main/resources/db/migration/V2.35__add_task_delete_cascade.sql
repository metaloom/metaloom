-- Deleting a task must cascade to its dependents:
--   task -> comment (comments on the task)
--   comment -> comment (the reply subtree of a deleted comment)
--   task -> reaction (reactions on the task)
--   comment -> reaction (reactions on a cascade-deleted comment)
--   task -> asset_task (asset assignment links)
-- annotation_task already cascades (V2.16).

ALTER TABLE "comment" DROP CONSTRAINT "comment_task_uuid_fkey";
ALTER TABLE "comment" ADD FOREIGN KEY ("task_uuid") REFERENCES "task" ("uuid") ON DELETE CASCADE;

ALTER TABLE "comment" DROP CONSTRAINT "comment_parent_uuid_fkey";
ALTER TABLE "comment" ADD FOREIGN KEY ("parent_uuid") REFERENCES "comment" ("uuid") ON DELETE CASCADE;

ALTER TABLE "reaction" DROP CONSTRAINT "reaction_task_uuid_fkey";
ALTER TABLE "reaction" ADD FOREIGN KEY ("task_uuid") REFERENCES "task" ("uuid") ON DELETE CASCADE;

ALTER TABLE "reaction" DROP CONSTRAINT "reaction_comment_uuid_fkey";
ALTER TABLE "reaction" ADD FOREIGN KEY ("comment_uuid") REFERENCES "comment" ("uuid") ON DELETE CASCADE;

ALTER TABLE "asset_task" DROP CONSTRAINT "asset_task_task_uuid_fkey";
ALTER TABLE "asset_task" ADD FOREIGN KEY ("task_uuid") REFERENCES "task" ("uuid") ON DELETE CASCADE;
