-- Normalises pipeline_run_item.state onto the documented vocabulary.
--
-- V2.31 documents the vocabulary as PENDING, RUNNING, SUCCESS, FAILED, SKIPPED, and
-- PipelineRunItemDaoImpl treats SUCCESS/FAILED/SKIPPED as the terminal set. The engine's
-- own outcome enum spells the failure case FAILURE, and that name was written to the
-- column verbatim - so a failed item never matched the terminal set. It looked unfinished
-- to recovery and was invisible to result reuse.
--
-- The mapping is fixed in Java (ItemOutcome.FAILURE -> RunItemState.FAILED). This rewrites
-- the rows already on disk, which the typed read path would otherwise reject.
UPDATE pipeline_run_item SET state = 'FAILED' WHERE state = 'FAILURE';

-- Say what the vocabulary is on the column itself, now that it is enforced.
COMMENT ON COLUMN pipeline_run_item.state IS 'PENDING, RUNNING, SUCCESS, FAILED or SKIPPED';
