package io.metaloom.loom.db.model.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;

public interface PipelineNodeTaskDao extends CRUDDao<PipelineNodeTask> {

	/** Load every task recorded against one item, in creation order. */
	List<PipelineNodeTask> loadByItem(UUID itemUuid);

	List<PipelineNodeTask> loadByRun(UUID runUuid);

	Page<PipelineNodeTask> loadPageByRun(UUID runUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection);

	/**
	 * Load a task by its idempotency key.
	 *
	 * @return the existing task, or null when this node has not run against the item
	 */
	PipelineNodeTask loadByItemAndNode(UUID itemUuid, String nodeId);

	/**
	 * Find tasks whose lease has lapsed.
	 *
	 * <p>A worker that accepts a task and then dies leaves it {@code RUNNING}
	 * forever. This is the reaper's query: it is what turns a dead worker into a
	 * reassignment rather than a stalled run.</p>
	 *
	 * @param now   the cutoff; leases expiring before this are considered lost
	 * @param limit maximum rows to reclaim in one sweep
	 */
	List<PipelineNodeTask> loadExpiredLeases(Instant now, int limit);

	/**
	 * @param runUuid the run
	 * @param state   one of PENDING, RUNNING, COMPLETED, FAILED, SKIPPED, DEAD_LETTER
	 */
	long countByRunAndState(UUID runUuid, String state);

	/**
	 * How many tasks a worker currently holds, across all runs.
	 *
	 * <p>Feeds the per-worker in-flight cap so one worker cannot be handed more than
	 * it can chew.</p>
	 */
	long countLeasedBy(String processorNodeId);

	/**
	 * Create an unsaved task in {@code PENDING}.
	 *
	 * @param userUuid who owns the run
	 * @param itemUuid the item the node runs against
	 * @param runUuid  the run, denormalised
	 * @param nodeId   graph-local node id
	 * @param nodeKind node kind, used for routing
	 */
	PipelineNodeTask createNodeTask(UUID userUuid, UUID itemUuid, UUID runUuid, String nodeId, String nodeKind);

}
