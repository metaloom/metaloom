-- ---------------------------------------------------------------------------
-- Node output reuse: what a node emitted for an asset, keyed by what it was asked.
--
-- Until now every pipeline run recomputed every node, even when the same node had already run on
-- the same asset with the same settings in another pipeline. The typed payload tables hold the
-- domain result, but nothing recorded the port values a node handed downstream, so a node had no way
-- to skip its work and still feed the rest of the graph.
--
-- NOT the same as asset_node_result (V2.45):
--   asset_node_result   is the ledger. One row per (asset, node_kind, node_id): "did this node
--                       instance run here, and how did it go". Overwritten on every run.
--   asset_node_output   is the reuse store. One row per (asset, node_kind, reuse_key): "this is what
--                       the node emitted when given exactly these options and inputs". A different
--                       configuration, or another element of a fanned-out sequence, is another row.
--
-- reuse_key is computed by the node itself (AbstractMediaNode in cortex/common): a SHA-256 over the
-- node kind, its reuse version, its options and its non-media input values. Loom never interprets
-- it; it only matches it. node_id is not part of the key, so a second pipeline running the same kind
-- with the same settings finds the first pipeline's output.
--
-- outputs is the node's port values exactly as they crossed the wire: a map of port id to
-- {contentType, cardinality, values}. It is a replay copy, not the domain record - the typed
-- component tables stay authoritative, and deleting rows here only costs a recomputation.
-- ---------------------------------------------------------------------------
CREATE TABLE "asset_node_output" (
  "uuid"             uuid NOT NULL DEFAULT uuidv7(),
  "asset_uuid"       uuid NOT NULL,
  "node_kind"        varchar NOT NULL,
  "reuse_key"        varchar NOT NULL,
  "node_id"          varchar NOT NULL DEFAULT '',
  "producer_version" varchar NOT NULL DEFAULT '',
  "state"            varchar NOT NULL,
  "reason"           varchar,
  "outputs"          jsonb NOT NULL,
  "run_uuid"         uuid,
  "created"          timestamp NOT NULL DEFAULT now(),
  "edited"           timestamp NOT NULL DEFAULT now(),
  PRIMARY KEY ("uuid"),
  CONSTRAINT "asset_node_output_asset_uuid_fkey" FOREIGN KEY ("asset_uuid") REFERENCES "asset" ("uuid") ON DELETE CASCADE,
  CONSTRAINT "asset_node_output_run_uuid_fkey" FOREIGN KEY ("run_uuid") REFERENCES "pipeline_run" ("uuid") ON DELETE SET NULL,
  CONSTRAINT "asset_node_output_key" UNIQUE ("asset_uuid", "node_kind", "reuse_key"),
  -- A failure is never stored: replaying one would turn a transient outage into a permanent answer.
  CONSTRAINT "asset_node_output_state_check" CHECK ("state" IN ('SUCCESS', 'SKIPPED'))
);

-- The unique key already serves the lookup (asset, kind, key) and the per-asset invalidation.
-- These two serve the operator sweeps: drop everything one kind produced, and detach a pruned run.
CREATE INDEX "idx_asset_node_output_node_kind" ON "asset_node_output" ("node_kind");
CREATE INDEX "idx_asset_node_output_run_uuid" ON "asset_node_output" ("run_uuid");

COMMENT ON TABLE "asset_node_output" IS 'Reuse store: the port values a node emitted for an asset, keyed by a hash of its options and inputs, so a later run of the same node with the same settings can skip its work. A replay copy - the typed component tables remain the domain record.';
COMMENT ON COLUMN "asset_node_output"."reuse_key" IS 'SHA-256 the node computes over its kind, reuse version, options and non-media inputs. Opaque to Loom: matched, never parsed.';
COMMENT ON COLUMN "asset_node_output"."node_id" IS 'Graph-local id of the node instance that last wrote the row. Informational; not part of the key.';
COMMENT ON COLUMN "asset_node_output"."state" IS 'SUCCESS or SKIPPED - mirrors ResultState in the Cortex node API. FAILED is never stored.';
COMMENT ON COLUMN "asset_node_output"."outputs" IS 'Port id -> {contentType, cardinality, values}: the wire values the node emitted, replayed verbatim on a hit.';
