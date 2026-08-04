package io.metaloom.loom.pipeline.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.loom.pipeline.model.NodeTask;

/**
 * Test double standing in for a Cortex worker.
 *
 * <p>Records every dispatched task and lets a test decide the outcome, so the whole
 * evaluation model can be exercised without a WebSocket, a worker, or a database.
 * That separation is the point of the {@link NodeDispatcher} SPI.</p>
 */
public class FakeNodeDispatcher implements NodeDispatcher {

	private final List<NodeTask> dispatched = new ArrayList<>();
	private final Map<String, Boolean> acceptByKind = new LinkedHashMap<>();
	private boolean acceptAll = true;

	@Override
	public String dispatch(NodeTask task) {
		dispatched.add(task);
		return acceptByKind.getOrDefault(task.getNodeKind(), acceptAll) ? WORKER_ID : null;
	}

	/** Identity reported for accepted tasks. */
	public static final String WORKER_ID = "fake-worker";

	/** Make dispatch fail for one node kind, simulating "no worker supports this". */
	public FakeNodeDispatcher rejectKind(String kind) {
		acceptByKind.put(kind, false);
		return this;
	}

	/** Make every dispatch fail. */
	public FakeNodeDispatcher rejectAll() {
		acceptAll = false;
		return this;
	}

	/** @return every task handed over, in dispatch order */
	public List<NodeTask> dispatched() {
		return dispatched;
	}

	/** @return the node ids dispatched, in order */
	public List<String> dispatchedNodeIds() {
		return dispatched.stream().map(NodeTask::getNodeId).toList();
	}

	/**
	 * @param nodeId the node
	 * @return the task dispatched for it
	 * @throws AssertionError when nothing was dispatched for that node
	 */
	public NodeTask taskFor(String nodeId) {
		return dispatched.stream()
			.filter(t -> t.getNodeId().equals(nodeId))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No task was dispatched for node '" + nodeId
				+ "'. Dispatched: " + dispatchedNodeIds()));
	}

	/**
	 * @param nodeId the node
	 * @param itemId which item's execution
	 * @return the task dispatched for that node on that item
	 * @throws AssertionError when nothing matches
	 */
	public NodeTask taskFor(String nodeId, String itemId) {
		return dispatched.stream()
			.filter(t -> t.getNodeId().equals(nodeId) && itemId.equals(t.getItemId()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No task was dispatched for node '" + nodeId
				+ "' on item '" + itemId + "'. Dispatched: " + dispatchedNodeIds()));
	}

	/**
	 * Every task dispatched for one node, in dispatch order.
	 *
	 * <p>The plural of {@link #taskFor(String)}, for a node downstream of a fan-out: it runs once
	 * per element, and a test that only ever saw the first execution would miss exactly the
	 * per-element behaviour it meant to check.</p>
	 */
	public List<NodeTask> tasksFor(String nodeId) {
		return dispatched.stream().filter(t -> t.getNodeId().equals(nodeId)).toList();
	}

	public boolean wasDispatched(String nodeId) {
		return dispatched.stream().anyMatch(t -> t.getNodeId().equals(nodeId));
	}

	public void clear() {
		dispatched.clear();
	}
}
