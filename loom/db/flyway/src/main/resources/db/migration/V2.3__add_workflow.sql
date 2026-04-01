
CREATE TYPE "task_status" AS ENUM (
  'PENDING',
  'REJECTED',
  'ACCEPTED',
  'REVIEW'
);

CREATE TABLE "task" (
  "uuid" uuid DEFAULT uuid_generate_v4 (),
  "title" varchar NOT NULL,
  "content" varchar,
  "due_date" timestamp,
  "status" task_status DEFAULT 'PENDING',
  "priority" int,

  "created" timestamp NOT NULL DEFAULT (now()),
  "creator_uuid" uuid NOT NULL,
  "edited" timestamp NOT NULL DEFAULT (now()),
  "editor_uuid" uuid NOT NULL,

  PRIMARY KEY ("uuid")
);
COMMENT ON COLUMN "task"."title" IS 'Title of the task';
COMMENT ON COLUMN "task"."content" IS 'Description of the task';
COMMENT ON COLUMN "task"."status" IS 'Current status of the task (e.g. PENDING, REJECTED)';
ALTER TABLE "task" ADD FOREIGN KEY ("creator_uuid") REFERENCES "user" ("uuid");
ALTER TABLE "task" ADD FOREIGN KEY ("editor_uuid") REFERENCES "user" ("uuid");


