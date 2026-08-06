package io.metaloom.loom.db.model.pipeline;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.page.Page;

public interface PipelineNodeTaskDao extends CRUDDao<PipelineNodeTask> {

	/**
	 * Load every task recorded against one item, in creation order.
	 *
	 * <p>
	 * This is one row per <em>execution</em>, not per node: a node downstream of a fan-out has one
	 * per element. Recovery must keep them apart, or a half-finished fan-out comes back one element
	 * wide and the rest are stranded.
	 * </p>
	 */
	List<PipelineNodeTask> loadByItem(UUID itemUuid);

	List<PipelineNodeTask> loadByRun(UUID runUuid);

	Page<PipelineNodeTask> loadPageByRun(UUID runUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection);

	/**
	 * Load the most recent attempt at one execution.
	 *
	 * <p>
	 * The element is part of the key. A node downstream of a fan-out has one row per element, so
	 * {@code (item, node)} alone identifies an arbitrary one of them.
	 * </p>
	 *
	 * <p>
	 * Since re-execution, an execution may have several rows — one per generation. This returns the
	 * highest, which is what every caller means: settling a task updates the attempt that is running,
	 * and adopting an earlier run's result should adopt the operator's last word on it, not their
	 * first.
	 * </p>
	 *
	 * @param elementSeq which element of a fanned-out sequence; 0 when the node runs once per item
	 * @return the latest attempt, or null when this execution has not run against the item
	 */
	PipelineNodeTask loadByItemAndNode(UUID itemUuid, String nodeId, int elementSeq);

	/**
	 * Load one specific attempt at an execution.
	 *
	 * @param generation which attempt; 0 is the original run
	 * @return the task, or null when that attempt was never recorded
	 */
	PipelineNodeTask loadByItemAndNode(UUID itemUuid, String nodeId, int elementSeq, int generation);



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

	long countByRunAndState(UUID runUuid, NodeTaskState state);

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
