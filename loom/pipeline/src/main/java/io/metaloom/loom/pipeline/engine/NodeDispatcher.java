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
	 * <p>The identity of the chosen worker is returned rather than a bare boolean so
	 * it can be recorded against the task. Without it, "which machine produced this
	 * result?" and "which worker is holding the tasks that keep timing out?" are
	 * unanswerable - and those are the first two questions asked when a heterogeneous
	 * pool misbehaves.</p>
	 *
	 * @param task the work to perform
	 * @return the id of the worker that took it, or null when none could, in which
	 *         case the engine fails the node rather than waiting forever
	 */
	String dispatch(NodeTask task);

	/**
	 * Send a whole affinity segment to a worker.
	 *
	 * <p>Defaults to refusing, so an implementation that predates segments keeps
	 * working: the engine falls back to dispatching the segment's nodes one at a
	 * time rather than failing them.</p>
	 *
	 * @param task the segment
	 * @return the id of the worker that took it, or null when none could
	 */
	default String dispatch(io.metaloom.loom.pipeline.model.SegmentTask task) {
		return null;
	}
}
