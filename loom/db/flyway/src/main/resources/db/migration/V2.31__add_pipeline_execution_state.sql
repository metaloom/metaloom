-- Durable per-item and per-node execution state for pipeline runs.
--
-- Until now a run's progress lived only in the engine's heap, so a Loom restart
-- stranded every in-flight item mid-graph and there was no way to answer "where
-- is this item?" or "which node failed?" after the fact. These two tables make
-- that state survive a restart and become queryable.
--
-- No new permissions are introduced: both tables are sub-resources of a run and
-- are guarded by the existing READ_PIPELINE_RUN / UPDATE_PIPELINE_RUN.

CREATE TABLE pipeline_run_item (
    uuid           UUID NOT NULL DEFAULT uuid_generate_v4(),
    run_uuid       UUID NOT NULL,
    item_seq       BIGINT NOT NULL,
    media_path     VARCHAR NOT NULL,
    sha512         VARCHAR,
    size_bytes     BIGINT,
    state          VARCHAR NOT NULL DEFAULT 'PENDING',
    error_message  VARCHAR,
    meta           JSONB,
    created        TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    creator_uuid   UUID NOT NULL,
    edited         TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    editor_uuid    UUID NOT NULL,
    CONSTRAINT pipeline_run_item_pkey PRIMARY KEY (uuid),
    CONSTRAINT pipeline_run_item_run_uuid_fkey FOREIGN KEY (run_uuid) REFERENCES pipeline_run (uuid) ON DELETE CASCADE,
    CONSTRAINT pipeline_run_item_creator_uuid_fkey FOREIGN KEY (creator_uuid) REFERENCES "user" (uuid),
    CONSTRAINT pipeline_run_item_editor_uuid_fkey FOREIGN KEY (editor_uuid) REFERENCES "user" (uuid),
    -- Re-delivery of a source batch after a reconnect must not duplicate items.
    CONSTRAINT pipeline_run_item_unique_seq UNIQUE (run_uuid, item_seq)
);

CREATE INDEX idx_pipeline_run_item_run_uuid ON pipeline_run_item (run_uuid);
-- Drives "how much of this run is still outstanding?" without a sequential scan.
CREATE INDEX idx_pipeline_run_item_run_state ON pipeline_run_item (run_uuid, state);

COMMENT ON TABLE pipeline_run_item IS 'One media item discovered by a run''s source node';
COMMENT ON COLUMN pipeline_run_item.run_uuid IS 'The run this item belongs to';
COMMENT ON COLUMN pipeline_run_item.item_seq IS 'Discovery order within the run; unique per run and used for idempotent re-delivery';
COMMENT ON COLUMN pipeline_run_item.media_path IS 'Absolute path of the media as reported by the source';
COMMENT ON COLUMN pipeline_run_item.sha512 IS 'Content hash, once a hashing node has produced one';
COMMENT ON COLUMN pipeline_run_item.size_bytes IS 'Size in bytes, or -1 when the source could not determine it';
COMMENT ON COLUMN pipeline_run_item.state IS 'PENDING, RUNNING, SUCCESS, FAILED or SKIPPED';
COMMENT ON COLUMN pipeline_run_item.error_message IS 'Why the item failed, when it did';

CREATE TABLE pipeline_node_task (
    uuid             UUID NOT NULL DEFAULT uuid_generate_v4(),
    item_uuid        UUID NOT NULL,
    run_uuid         UUID NOT NULL,
    node_id          VARCHAR NOT NULL,
    node_kind        VARCHAR NOT NULL,
    state            VARCHAR NOT NULL DEFAULT 'PENDING',
    attempt          INTEGER NOT NULL DEFAULT 0,
    max_attempts     INTEGER NOT NULL DEFAULT 1,
    leased_by        VARCHAR,
    lease_expires_at TIMESTAMP WITHOUT TIME ZONE,
    started          TIMESTAMP WITHOUT TIME ZONE,
    finished         TIMESTAMP WITHOUT TIME ZONE,
    duration_ms      BIGINT,
    error_message    VARCHAR,
    outputs          JSONB,
    meta             JSONB,
    created          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    creator_uuid     UUID NOT NULL,
    edited           TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    editor_uuid      UUID NOT NULL,
    CONSTRAINT pipeline_node_task_pkey PRIMARY KEY (uuid),
    CONSTRAINT pipeline_node_task_item_uuid_fkey FOREIGN KEY (item_uuid) REFERENCES pipeline_run_item (uuid) ON DELETE CASCADE,
    CONSTRAINT pipeline_node_task_run_uuid_fkey FOREIGN KEY (run_uuid) REFERENCES pipeline_run (uuid) ON DELETE CASCADE,
    CONSTRAINT pipeline_node_task_creator_uuid_fkey FOREIGN KEY (creator_uuid) REFERENCES "user" (uuid),
    CONSTRAINT pipeline_node_task_editor_uuid_fkey FOREIGN KEY (editor_uuid) REFERENCES "user" (uuid),
    -- The idempotency key. Once retries exist duplicate delivery is inevitable,
    -- and a node must run at most once per item.
    CONSTRAINT pipeline_node_task_unique_node UNIQUE (item_uuid, node_id)
);

CREATE INDEX idx_pipeline_node_task_item_uuid ON pipeline_node_task (item_uuid);
CREATE INDEX idx_pipeline_node_task_run_state ON pipeline_node_task (run_uuid, state);
-- The lease reaper's only query: find RUNNING tasks whose lease has expired.
-- Partial, because that is a small slice of a table which grows to millions of
-- terminal rows.
CREATE INDEX idx_pipeline_node_task_expired_lease ON pipeline_node_task (lease_expires_at)
    WHERE state = 'RUNNING';

COMMENT ON TABLE pipeline_node_task IS 'One node execution against one media item';
COMMENT ON COLUMN pipeline_node_task.item_uuid IS 'The item this node ran against';
COMMENT ON COLUMN pipeline_node_task.run_uuid IS 'Denormalised from the item so run-scoped queries need no join';
COMMENT ON COLUMN pipeline_node_task.node_id IS 'Graph-local node id, unique within the pipeline definition';
COMMENT ON COLUMN pipeline_node_task.node_kind IS 'Node kind, used to route the task to a capable worker';
COMMENT ON COLUMN pipeline_node_task.state IS 'PENDING, RUNNING, COMPLETED, FAILED, SKIPPED or DEAD_LETTER';
COMMENT ON COLUMN pipeline_node_task.attempt IS 'How many times this task has been dispatched';
COMMENT ON COLUMN pipeline_node_task.max_attempts IS 'Attempt ceiling before the task is dead-lettered';
COMMENT ON COLUMN pipeline_node_task.leased_by IS 'Processor node id currently holding the task';
COMMENT ON COLUMN pipeline_node_task.lease_expires_at IS 'When the lease lapses and the task returns to PENDING';
COMMENT ON COLUMN pipeline_node_task.duration_ms IS 'Worker-reported execution time';
COMMENT ON COLUMN pipeline_node_task.error_message IS 'Failure detail or skip reason';
COMMENT ON COLUMN pipeline_node_task.outputs IS 'Node outputs, consumed as inputs by downstream nodes';
