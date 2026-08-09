package io.metaloom.loom.db.model.pipeline;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import io.metaloom.loom.api.pipeline.PipelineRunStatus;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.vertx.core.json.JsonObject;

public interface PipelineRunDao extends CRUDDao<PipelineRun> {

	/**
	 * Load all pipeline runs for a specific pipeline.
	 */
	List<PipelineRun> loadByPipeline(UUID pipelineUuid);

	/**
	 * Load a paged list of pipeline runs for a specific pipeline.
	 */
	Page<PipelineRun> loadPageByPipeline(UUID pipelineUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	/**
	 * Load the latest pipeline run for a pipeline.
	 */
	PipelineRun loadLatestByPipeline(UUID pipelineUuid);

	/**
	 * Load every run in the given status.
	 *
	 * <p>Recovery uses this to find runs left mid-flight by a restart: a process that
	 * dies never gets to move its runs out of {@code RUNNING}, so on the next start
	 * that status means "was in progress when we stopped".</p>
	 */
	List<PipelineRun> loadByStatus(PipelineRunStatus status);

	/**
	 * Aggregate run counters across all pipelines into daily buckets (by run start time).
	 *
	 * <p>Only days that actually have runs are returned, ordered oldest first. Callers are
	 * responsible for zero-filling missing days.</p>
	 */
	List<PipelineRunDayStats> loadDailyStats(LocalDateTime since);

	/**
	 * Create a new pipeline run record.
	 */
	PipelineRun createPipelineRun(UUID userUuid, UUID pipelineUuid, int pipelineVersion);

	/**
	 * Create an ad-hoc run: no pipeline row, the definition carried in {@code meta.definition}.
	 *
	 * <p>The definition is the same JSON {@code validate_pipeline} and {@code PipelineGraphParser}
	 * accept, so an ad-hoc run needs no new format and can be replayed by recovery after a restart.
	 * See {@code spec/chat/AGENTIC_NODE_EXECUTION.md}.</p>
	 *
	 * @param userUuid   the caller the run belongs to; ad-hoc runs are scoped to their creator
	 * @param definition the executable graph, stored verbatim
	 */
	PipelineRun createAdhocRun(UUID userUuid, JsonObject definition);

	/**
	 * Load a paged list of the ad-hoc runs a user started, newest first.
	 *
	 * <p>Ad-hoc runs are not reachable through {@link #loadPageByPipeline(UUID, UUID, int, List, SortKey, SortDirection)}
	 * - they belong to no pipeline - so this is the only listing that shows them. Backed by
	 * {@code idx_pipeline_run_adhoc_creator}.</p>
	 */
	Page<PipelineRun> loadAdhocPageByCreator(UUID creatorUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection);

	/**
	 * How many of a user's ad-hoc runs are in a non-terminal status.
	 *
	 * <p>This is the concurrency quota's source of truth rather than an in-memory counter, because the
	 * counter would reset on restart while the runs it was counting are still recovered and still
	 * running.</p>
	 */
	int countActiveAdhocByCreator(UUID creatorUuid);

}