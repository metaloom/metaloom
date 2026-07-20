-- Convert the previously unused integer task.priority column into a proper enum.
CREATE TYPE "task_priority" AS ENUM ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL');

ALTER TABLE "task" DROP COLUMN "priority";
ALTER TABLE "task" ADD COLUMN "priority" task_priority NOT NULL DEFAULT 'MEDIUM';

COMMENT ON COLUMN "task"."priority" IS 'Priority of the task (LOW, MEDIUM, HIGH, CRITICAL)';
