-- Support looking a media item up by its path, across runs.
--
-- Results are reused between runs so a second run of the same pipeline does not
-- recompute what is already known. At the moment an item is discovered its hash is
-- not yet known - the source reports a path and a size, nothing more - so the path
-- is the only key available to find what a previous run learned about the file.
--
-- Without this index that lookup is a sequential scan of a table which grows to one
-- row per file per run, on the hot path of every dispatch decision.

CREATE INDEX idx_pipeline_run_item_media_path ON pipeline_run_item (media_path);

COMMENT ON INDEX idx_pipeline_run_item_media_path IS
    'Cross-run lookup of a previously processed file, keyed by path';
