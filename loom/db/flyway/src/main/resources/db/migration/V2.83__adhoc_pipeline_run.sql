-- Ad-hoc ("pipelineless") node runs: a run that executes a definition submitted with the request
-- instead of one stored in the pipeline catalog. See spec/chat/AGENTIC_NODE_EXECUTION.md.
--
-- The definition is carried in pipeline_run.meta.definition - the same JSON validate_pipeline and
-- PipelineGraphParser already accept - so there is no new format, no catalog pollution and no
-- reaper for ephemeral pipeline rows. The run row IS the record.
--
-- pipeline_uuid keeps its foreign key. A NULL always satisfies a foreign key, so ON DELETE CASCADE
-- is unchanged for the rows that do have one: deleting a pipeline still takes its runs with it, and
-- an ad-hoc run is unaffected because it never belonged to a pipeline in the first place.
ALTER TABLE "pipeline_run" ALTER COLUMN "pipeline_uuid" DROP NOT NULL;

-- The discriminator, not a nullability probe. "pipeline_uuid IS NULL" would also be true of a
-- future run whose pipeline was hard-deleted; kind states intent and survives that. VARCHAR + CHECK
-- rather than an enum, matching pipeline_run.status (see V2.29 and PipelineRunStatus).
ALTER TABLE "pipeline_run" ADD COLUMN "kind" VARCHAR NOT NULL DEFAULT 'PIPELINE';

ALTER TABLE "pipeline_run" ADD CONSTRAINT "pipeline_run_kind_check"
  CHECK ("kind" IN ('PIPELINE', 'ADHOC'));

-- A pipeline-kind run must name its pipeline; an ad-hoc one must not. Enforcing the pairing here
-- means no consumer has to defend against the impossible third state (a 'PIPELINE' run with no
-- pipeline, or an 'ADHOC' run pointing at one).
ALTER TABLE "pipeline_run" ADD CONSTRAINT "pipeline_run_kind_pipeline_uuid_check"
  CHECK (("kind" = 'PIPELINE' AND "pipeline_uuid" IS NOT NULL)
      OR ("kind" = 'ADHOC'    AND "pipeline_uuid" IS NULL));

-- "my jobs, newest first" and the per-user concurrency count are the only two queries ad-hoc runs
-- are ever read by; they are not reachable through /pipelines/:uuid/runs, which is what
-- idx_pipeline_run_pipeline_uuid serves. Partial, because ad-hoc rows are the small minority.
CREATE INDEX "idx_pipeline_run_adhoc_creator" ON "pipeline_run" ("creator_uuid", "started" DESC)
  WHERE "kind" = 'ADHOC';

COMMENT ON COLUMN "pipeline_run"."kind" IS
  'PIPELINE = started from a stored pipeline row; ADHOC = inline definition in meta.definition';
COMMENT ON COLUMN "pipeline_run"."pipeline_uuid" IS
  'The pipeline this run executes; NULL for kind = ADHOC, which carries its definition in meta';

-- An ad-hoc run outlives the tool call that started it, so completion needs the durable signal the
-- notification table already provides rather than a second channel. notification.type is VARCHAR +
-- CHECK precisely so that adding a value is DROP CONSTRAINT + ADD CONSTRAINT (see the V2.70
-- header), which is what this is.
ALTER TABLE "notification" DROP CONSTRAINT "notification_type_check";
ALTER TABLE "notification" ADD CONSTRAINT "notification_type_check" CHECK ("type" IN (
  'TASK_ASSIGNED', 'TASK_UNASSIGNED', 'TASK_STATUS_CHANGED',
  'TASK_COMMENT', 'COMMENT_REPLY', 'PIPELINE_RUN_FAILED', 'NODE_RUN_COMPLETED'));
