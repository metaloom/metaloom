-- Small, disposable renderings of what a node emitted, so a human can look at it.
--
-- A port carrying produced media does not carry the media. ThumbnailNode and
-- ImageManipulationNode both emit an artifact/image port whose value is an
-- *absolute path on the worker that produced it*. Loom cannot reach that
-- filesystem and has no route to those bytes, so until now the debugging view
-- could name a file nobody could look at.
--
-- Why inline in this table rather than in `attachment`:
--
--   * A preview is run-scoped diagnostics. It is pruned with the run that
--     produced it, exactly like the outputs column beside it. `attachment` is
--     catalogue state that outlives every run, and putting a debugging artefact
--     there would give it the wrong retention and the wrong lifecycle.
--   * Promoting a node's real output into something durable and addressable is a
--     separate feature (see the REST/Cortex binary-handling plan). This is not
--     that feature, and it must not quietly become the way it is done.
--
-- Size is bounded at the source, not here: the worker caps a preview at
-- NodePreview.DEFAULT_MAX_BYTES (96 KiB) with the longest edge at 512px, and
-- drops rather than truncates anything larger — half a JPEG is not a smaller
-- JPEG. Previews are also opt-in per run (NodeTask.capturePreviews), so an
-- ordinary production run writes NULL here for every one of its rows.
--
-- Nullable with no default and no backfill: absent means "this run did not ask
-- for previews", which is the correct reading for every row that already exists.
--
-- No new permissions: still a sub-resource of a run, guarded by the existing
-- READ_PIPELINE_RUN.

ALTER TABLE pipeline_node_task ADD COLUMN previews JSONB;

COMMENT ON COLUMN pipeline_node_task.previews IS
    'Opt-in per-run debugging previews keyed by output port id; each value is a NodePreview {mimeType, width, height, data (base64), skippedReason}. NULL when the run did not request previews. Run-scoped and pruned with the run — never catalogue state.';
