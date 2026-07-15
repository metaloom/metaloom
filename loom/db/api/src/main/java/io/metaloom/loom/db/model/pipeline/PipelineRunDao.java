package io.metaloom.loom.db.model.pipeline;

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
	 * Create a new pipeline run record.
	 */
	PipelineRun createPipelineRun(UUID userUuid, UUID pipelineUuid, int pipelineVersion);

}