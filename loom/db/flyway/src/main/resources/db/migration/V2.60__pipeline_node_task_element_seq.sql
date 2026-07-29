-- Per-element node execution state.
--
-- A node output port may now carry a *sequence* rather than a single value: one
-- image fans out into N detected faces, one transcript into N chapter texts.
-- Every node downstream of such a port whose own input takes one element runs
-- once per element, so "one node execution per item" stops being true and the
-- table's idempotency key has to grow a third column.
--
-- The fan-out deliberately stays *inside* one pipeline_run_item rather than
-- spawning child items: the item is the origin, which is what lets a later node
-- gather the branches back together per source asset with no lineage columns and
-- no second completion model. That is why this migration touches only
-- pipeline_node_task and leaves pipeline_run_item alone.
--
-- No new permissions: the table is still a sub-resource of a run, guarded by the
-- existing READ_PIPELINE_RUN / UPDATE_PIPELINE_RUN.

ALTER TABLE pipeline_node_task ADD COLUMN element_seq INTEGER NOT NULL DEFAULT 0;

-- The idempotency key becomes (item, node, element). A node that runs once per
-- item keeps element_seq = 0, so existing rows are already correct under the new
-- key and no backfill is needed.
ALTER TABLE pipeline_node_task DROP CONSTRAINT pipeline_node_task_unique_node;
ALTER TABLE pipeline_node_task
    ADD CONSTRAINT pipeline_node_task_unique_node UNIQUE (item_uuid, node_id, element_seq);

COMMENT ON COLUMN pipeline_node_task.element_seq IS
    'Which element of a fanned-out sequence this execution covers; 0 when the node runs once per item';
COMMENT ON COLUMN pipeline_node_task.outputs IS
    'Node outputs keyed by output port id; each value is a PortPayload carrying the declared content type, cardinality and origin-tagged elements';
