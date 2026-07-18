package io.metaloom.loom.pipeline.engine;

import io.metaloom.loom.pipeline.model.NodeTask;

/**
 * Hands a single {@link NodeTask} to a worker.
 *
 * <p>The engine depends on this interface rather than on a WebSocket so that the
 * whole evaluation model is testable without a running Cortex. The production
 * implementation lives in {@code loom/services/rest} and writes to the processor
 * WebSocket; tests use a fake that records tasks and replies on demand.</p>
 *
 * <p><strong>Phase 1 is push:</strong> the engine calls {@link #dispatch(NodeTask)}
 * as soon as a node becomes ready. A later phase is expected to invert this to a
 * pull with leases, which changes the transport but not the task payload.</p>
 */
public interface NodeDispatcher {

	/**
	 * Send a task to a worker.
	 *
	 * <p>Implementations must not block. The result arrives asynchronously via
	 * {@link PipelineRunEngine#onNodeTaskResult}.</p>
	 *
	 * @param task the work to perform
	 * @return true when the task was handed off; false when no worker could take it,
	 *         in which case the engine fails the node rather than waiting forever
	 */
	boolean dispatch(NodeTask task);
}
