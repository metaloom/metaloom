-- Re-execution: several attempts at the same node execution, kept side by side.
--
-- Stopping a run at a breakpoint is only half of debugging a pipeline. The other
-- half is changing the setting you suspect and running that same node over that
-- same input again — which is exactly what a face detector with a pose gate, or
-- a thumbnail node with a tile count, is worth stopping for.
--
-- Each attempt is a row rather than an overwrite. Comparing "before" with
-- "after" is the entire reason to re-execute; an UPDATE would destroy the
-- comparison at the moment it became interesting. So the idempotency key grows
-- a fourth column and (item, node, element) may now appear more than once.
--
-- Why an integer counter rather than a timestamp: the UI offers "attempt 1 / 2 /
-- 3", and ordering by wall clock would be wrong for two attempts recorded inside
-- the same millisecond. The engine assigns it (NodeExecState.generationFor), so
-- it is dense and gap-free per execution.
--
-- Generation 0 means "this execution ran once", which is true of every row that
-- already exists and of every row an ordinary production run will ever write —
-- re-execution requires a live engine, a breakpoint and an explicit request. The
-- DEFAULT therefore backfills correctly by itself.
--
-- No new permissions: still a sub-resource of a run, guarded by the existing
-- READ_PIPELINE_RUN / UPDATE_PIPELINE_RUN. Deletion still cascades from
-- pipeline_run_item, so extra generations are pruned with the run exactly like
-- the rows they sit beside.

ALTER TABLE pipeline_node_task ADD COLUMN generation INTEGER NOT NULL DEFAULT 0;

ALTER TABLE pipeline_node_task DROP CONSTRAINT pipeline_node_task_unique_node;
ALTER TABLE pipeline_node_task
    ADD CONSTRAINT pipeline_node_task_unique_node UNIQUE (item_uuid, node_id, element_seq, generation);

COMMENT ON COLUMN pipeline_node_task.generation IS
    'Which attempt at this execution the row records; 0 for the only run of an ordinary task, counting up per operator-requested re-execution of a node held at a breakpoint';
