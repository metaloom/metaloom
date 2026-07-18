package io.metaloom.loom.db.model.pipeline;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.vertx.core.json.JsonObject;

public interface PipelineVersionDao extends CRUDDao<PipelineVersion> {

	/**
	 * Load all pipeline versions for a specific pipeline.
	 */
	List<PipelineVersion> loadByPipeline(UUID pipelineUuid);

	/**
	 * Load pipeline versions by their UUIDs in a single query. Used to resolve the latest version of many pipelines at once without an N+1 lookup.
	 *
	 * @return the matching versions, in no particular order; unknown UUIDs are silently skipped
	 */
	List<PipelineVersion> loadByUuids(Collection<UUID> uuids);

	/**
	 * Load a paged list of pipeline versions for a specific pipeline.
	 */
	Page<PipelineVersion> loadPageByPipeline(UUID pipelineUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	/**
	 * Load the latest pipeline version for a pipeline.
	 */
	PipelineVersion loadLatestByPipeline(UUID pipelineUuid);

	/**
	 * Load a specific version of a pipeline.
	 */
	PipelineVersion loadByPipelineAndVersion(UUID pipelineUuid, int versionNumber);

	/**
	 * Create a new pipeline version.
	 */
	PipelineVersion createVersion(UUID userUuid, UUID pipelineUuid, int versionNumber, String name, String description, JsonObject definition, boolean enabled, int priority, boolean dryRun, JsonObject meta);

}