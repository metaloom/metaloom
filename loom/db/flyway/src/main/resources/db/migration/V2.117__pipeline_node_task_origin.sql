-- ---------------------------------------------------------------------------
-- Where a node execution's result came from.
--
-- A node task either did the work (COMPUTED) or handed on a result it already had: LOCAL for the
-- worker's in-heap cache, REMOTE for an output Loom stored for the same question (V2.116 node output
-- reuse, or the engine's own reuseResults adoption of an earlier run). Cortex has always reported
-- this on every task result; Loom dropped it, so a run could not say which of its steps were reused
-- and the time a pipeline spends could not be told apart from the time it saved.
--
-- NULL for rows written before this migration and for synthesised rows that carry no execution
-- (a skip, the source node). Readers treat NULL as COMPUTED, matching the wire contract.
-- ---------------------------------------------------------------------------
ALTER TABLE "pipeline_node_task" ADD COLUMN "origin" varchar;
ALTER TABLE "pipeline_node_task"
    ADD CONSTRAINT "pipeline_node_task_origin_check" CHECK ("origin" IS NULL OR "origin" IN ('COMPUTED', 'LOCAL', 'REMOTE'));

-- The performance view aggregates every task of a pipeline's recent runs by node.
CREATE INDEX IF NOT EXISTS "idx_pipeline_node_task_run_node" ON "pipeline_node_task" ("run_uuid", "node_id");

COMMENT ON COLUMN "pipeline_node_task"."origin" IS 'COMPUTED, LOCAL or REMOTE - mirrors ResultOrigin in the Cortex node API. LOCAL and REMOTE mean the result was reused rather than computed. NULL reads as COMPUTED.';
