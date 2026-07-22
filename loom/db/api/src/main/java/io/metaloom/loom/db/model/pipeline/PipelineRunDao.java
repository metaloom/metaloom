package io.metaloom.loom.db.model.pipeline;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;

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
	List<PipelineRun> loadByStatus(String status);

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

}