package io.metaloom.cortex.pipeline.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.FilesystemNode;
import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.common.event.DefaultPipelineEventBus;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;

/**
 * Base for node tests under the Loom-orchestrated model.
 *
 * <p>Replaces {@code AbstractPipelineNodeTest}, which built a real {@code Pipeline}
 * and ran the in-Cortex DAG executor. That executor now lives on the Loom side, so
 * a Cortex node test has no business constructing a graph: it should verify what a
 * <em>node</em> does, given a media item and its upstream inputs.</p>
 *
 * <p>The chain here is deliberately linear and executed directly - node 1, then node
 * 2 with node 1's outputs, and so on. That is exactly the guarantee the Loom engine
 * offers a node, without pulling in scheduling, concurrency or branch routing.
 * Ordering, skip semantics, filter branches and dry-run <em>dispatch</em> behaviour
 * are covered by {@code PipelineRunEngineTest} on the Loom side.</p>
 *
 * <p>The assertion surface is unchanged from the old harness - {@code execute(...)},
 * {@code adapt(...)}, {@code assertCompletionEvent(...)} and
 * {@code assertTrackingEvent(...)} all behave the same - so migrating a test is a
 * one-line change of the {@code extends} clause.</p>
 *
 * <h3>Template</h3>
 *
 * <pre>
 * class MyNodePipelineTest extends AbstractNodeChainTest {
 *
 *     &#64;Test
 *     void testProcessing() {
 *         LoomMedia media = StubLoomMedia.ofFile(tempFile);
 *         PipelineNode node = adapt(new MyNode(...));
 *
 *         PipelineResult result = execute(media, node);
 *
 *         assertThat(result).isSuccess().hasCompletedNode("my-node");
 *         assertCompletionEvent("my-node");
 *     }
 * }
 * </pre>
 */
public abstract class AbstractNodeChainTest {

	protected DefaultPipelineEventBus eventBus;

	protected List<NodeCompletionEvent> completionEvents;
	protected List<PipelineTrackingEvent> trackingEvents;

	@BeforeEach
	void setUpNodeChain() {
		eventBus = new DefaultPipelineEventBus();
		completionEvents = new CopyOnWriteArrayList<>();
		trackingEvents = new CopyOnWriteArrayList<>();

		eventBus.subscribeAll(completionEvents::add);
		eventBus.subscribeTracking(trackingEvents::add);
	}

	@AfterEach
	void tearDownNodeChain() {
		eventBus.clear();
	}

	/**
	 * Run the given nodes in order over one media item.
	 *
	 * @param media the media to process
	 * @param nodes the nodes, executed left to right; each sees the outputs of those
	 *              before it
	 * @return the collected results
	 */
	protected PipelineResult execute(LoomMedia media, PipelineNode... nodes) {
		return execute("test-pipeline", media, nodes);
	}

	/**
	 * Run the given nodes in order, under a named pipeline.
	 */
	protected PipelineResult execute(String pipelineName, LoomMedia media, PipelineNode... nodes) {
		return run(pipelineName, media, false, nodes);
	}

	/**
	 * Run the chain in dry-run mode: every node is skipped and none is invoked.
	 *
	 * <p>Dry-run is decided by the Loom engine, which simply does not dispatch. This
	 * reproduces the observable outcome for a node test.</p>
	 *
	 * @param media the media that would have been processed
	 * @param nodes the nodes that would have run
	 * @return a result in which every node is skipped
	 */
	protected PipelineResult executeDryRun(LoomMedia media, PipelineNode... nodes) {
		return run("dryrun-test", media, true, nodes);
	}

	/**
	 * Run a chain ending in a filter, then apply the branch decision.
	 *
	 * <p>Reproduces what the Loom engine does with a filter verdict: the node wired
	 * to the matching branch runs, the other is skipped. Kept here so filter tests can
	 * still assert that {@code filter_passed} actually routes, without reconstructing
	 * a graph - branch <em>evaluation</em> is covered on the Loom side by
	 * {@code PipelineRunEngineTest}.</p>
	 *
	 * @param media      the media item
	 * @param filterId   id of the filter whose verdict decides the branch
	 * @param chain      nodes to run in order, ending with the filter
	 * @param passNode   node wired to the PASS branch
	 * @param rejectNode node wired to the REJECT branch
	 * @return results for the chain plus both branch nodes
	 */
	protected PipelineResult executeWithBranches(LoomMedia media, String filterId, PipelineNode[] chain,
		PipelineNode passNode, PipelineNode rejectNode) {

		PipelineResult chainResult = execute("filter-routing-test", media, chain);
		Map<String, NodeResult> results = new LinkedHashMap<>(chainResult.getNodeResults());

		NodeResult filterResult = results.get(filterId);
		boolean passed = filterResult != null
			&& Boolean.TRUE.equals(filterResult.getOutput(PipelineNode.FILTER_PASSED));

		PipelineNode taken = passed ? passNode : rejectNode;
		PipelineNode notTaken = passed ? rejectNode : passNode;

		NodeResult takenResult;
		try {
			takenResult = taken.process(media, Map.copyOf(results));
		} catch (Exception e) {
			takenResult = NodeResult.failed(taken.id(), 0, String.valueOf(e));
		}
		results.put(taken.id(), takenResult);
		results.put(notTaken.id(), NodeResult.skipped(notTaken.id(),
			"Filter branch not taken on dependency " + filterId));

		eventBus.publish(new NodeCompletionEvent(taken.id(), media, takenResult));
		emitTracking(trackingTypeFor(takenResult.getState()), "filter-routing-test", taken.id(), media);

		return new PipelineResult("filter-routing-test", media, results,
			chainResult.getTotalDurationMs(), false);
	}

	private PipelineResult run(String pipelineName, LoomMedia media, boolean dryRun, PipelineNode... nodes) {
		long start = System.currentTimeMillis();
		Map<String, NodeResult> results = new LinkedHashMap<>();

		// The source node's result is synthesised by the engine rather than executed,
		// so a node test sees the same starting state a real run would produce. It
		// still emits events, because from a subscriber's point of view the source
		// completed just like any other node.
		NodeResult sourceResult = dryRun
			? NodeResult.skipped(SOURCE_NODE_ID, "dry-run")
			: NodeResult.success(SOURCE_NODE_ID, 0, Map.of("path", media.absolutePath(), "source", "asset"));
		results.put(SOURCE_NODE_ID, sourceResult);
		eventBus.publish(new NodeCompletionEvent(SOURCE_NODE_ID, media, sourceResult));
		emitTracking(trackingTypeFor(sourceResult.getState()), pipelineName, SOURCE_NODE_ID, media);

		for (PipelineNode node : nodes) {
			NodeResult result;
			if (dryRun) {
				result = NodeResult.skipped(node.id(), "dry-run");
			} else {
				emitTracking(PipelineTrackingEvent.Type.NODE_STARTED, pipelineName, node.id(), media);
				long nodeStart = System.currentTimeMillis();
				try {
					result = node.process(media, Map.copyOf(results));
					if (result == null) {
						result = NodeResult.failed(node.id(), System.currentTimeMillis() - nodeStart,
							"Node returned no result");
					}
				} catch (Exception e) {
					result = NodeResult.failed(node.id(), System.currentTimeMillis() - nodeStart, String.valueOf(e));
				}
			}

			results.put(node.id(), result);
			eventBus.publish(new NodeCompletionEvent(node.id(), media, result));
			emitTracking(trackingTypeFor(result.getState()), pipelineName, node.id(), media);
		}

		return new PipelineResult(pipelineName, media, results, System.currentTimeMillis() - start, dryRun);
	}

	/** Node id the synthesised source result is recorded under. */
	protected static final String SOURCE_NODE_ID = "asset-source";

	private void emitTracking(PipelineTrackingEvent.Type type, String pipelineName, String nodeId, LoomMedia media) {
		eventBus.publishTracking(new PipelineTrackingEvent(type, pipelineName, nodeId, media.absolutePath()));
	}

	private static PipelineTrackingEvent.Type trackingTypeFor(ResultState state) {
		switch (state) {
			case SUCCESS:
				return PipelineTrackingEvent.Type.NODE_COMPLETED;
			case FAILED:
				return PipelineTrackingEvent.Type.NODE_FAILED;
			default:
				return PipelineTrackingEvent.Type.NODE_SKIPPED;
		}
	}

	/**
	 * Wrap a legacy cortex {@link FilesystemNode} with the default test settings
	 * (PARALLEL, blocking, concurrency 1).
	 */
	protected CortexNodeAdapter adapt(FilesystemNode<?, ?> node) {
		return adapt(node, NodeMode.PARALLEL, true, 1);
	}

	/**
	 * Wrap a legacy cortex {@link FilesystemNode} with explicit settings.
	 */
	protected CortexNodeAdapter adapt(FilesystemNode<?, ?> node, NodeMode mode, boolean blocking, int concurrency) {
		return new CortexNodeAdapter(node, mode, blocking, concurrency);
	}

	/**
	 * Assert that a {@link NodeCompletionEvent} was received for the given node id.
	 */
	protected NodeCompletionEvent assertCompletionEvent(String nodeId) {
		return completionEvents.stream()
			.filter(e -> nodeId.equals(e.getNodeId()))
			.findFirst()
			.orElseThrow(() -> new AssertionError(
				"No completion event for node '" + nodeId + "'. Received: "
					+ completionEvents.stream().map(NodeCompletionEvent::getNodeId).toList()));
	}

	/**
	 * Assert that a tracking event of the given type was received for the given node.
	 */
	protected PipelineTrackingEvent assertTrackingEvent(String nodeId, PipelineTrackingEvent.Type type) {
		return trackingEvents.stream()
			.filter(e -> nodeId.equals(e.getNodeId()) && type == e.getType())
			.findFirst()
			.orElseThrow(() -> new AssertionError(
				"No " + type + " tracking event for node '" + nodeId + "'. Received: "
					+ trackingEvents.stream().map(e -> e.getNodeId() + ":" + e.getType()).toList()));
	}
}
