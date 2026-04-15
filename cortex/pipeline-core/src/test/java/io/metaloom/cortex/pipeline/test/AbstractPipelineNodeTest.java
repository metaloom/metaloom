package io.metaloom.cortex.pipeline.test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.metaloom.cortex.pipeline.common.event.DefaultPipelineEventBus;
import io.metaloom.cortex.pipeline.core.DefaultPipeline;
import io.metaloom.cortex.pipeline.core.executor.ReactivePipelineExecutor;
import io.metaloom.cortex.pipeline.core.node.AssetSourceNode;

/**
 * Abstract base for pipeline node tests. Provides:
 * <ul>
 *   <li>Executor and event bus lifecycle</li>
 *   <li>Collected completion and tracking events for assertions</li>
 *   <li>Helpers to build and execute simple pipelines</li>
 * </ul>
 *
 * <p>Subclasses create the node under test and a {@link StubLoomMedia},
 * then call {@link #execute(LoomMedia, PipelineNode...)} to run a pipeline and get results.</p>
 *
 * <h3>Template</h3>
 * <pre>
 * class MyNodePipelineTest extends AbstractPipelineNodeTest {
 *
 *     &#64;Test
 *     void testProcessing() {
 *         LoomMedia media = StubLoomMedia.ofFile(tempFile);
 *         MyNode node = new MyNode(...);
 *         PipelineNode adapter = adapt(node, NodeMode.PARALLEL, true, 1);
 *
 *         PipelineResult result = execute(media, adapter);
 *
 *         assertThat(result).isSuccess().hasCompletedNode("my-node");
 *         assertCompletionEvent("my-node");
 *     }
 * }
 * </pre>
 */
public abstract class AbstractPipelineNodeTest {

	protected ReactivePipelineExecutor executor;
	protected DefaultPipelineEventBus eventBus;

	protected List<NodeCompletionEvent> completionEvents;
	protected List<PipelineTrackingEvent> trackingEvents;

	@BeforeEach
	void setUpPipeline() {
		eventBus = new DefaultPipelineEventBus();
		executor = new ReactivePipelineExecutor(4, eventBus);
		completionEvents = new CopyOnWriteArrayList<>();
		trackingEvents = new CopyOnWriteArrayList<>();

		eventBus.subscribeAll(completionEvents::add);
		eventBus.subscribeTracking(trackingEvents::add);
	}

	@AfterEach
	void tearDownPipeline() {
		executor.shutdown();
		eventBus.clear();
	}

	/**
	 * Build and execute a linear pipeline: AssetSourceNode → node1 → node2 → ...
	 *
	 * @param media the media to process
	 * @param nodes one or more pipeline nodes to chain (in order)
	 * @return the pipeline execution result
	 */
	protected PipelineResult execute(LoomMedia media, PipelineNode... nodes) {
		return execute("test-pipeline", media, nodes);
	}

	/**
	 * Build and execute a linear pipeline with a custom name.
	 */
	protected PipelineResult execute(String pipelineName, LoomMedia media, PipelineNode... nodes) {
		AssetSourceNode source = new AssetSourceNode(media);
		PipelineNode prev = source;
		for (PipelineNode node : nodes) {
			prev.connectTo(node);
			prev = node;
		}
		Pipeline pipeline = DefaultPipeline.builder(pipelineName)
				.source(source)
				.build();
		return executor.execute(pipeline, media);
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
								+ trackingEvents.stream()
										.map(e -> e.getNodeId() + ":" + e.getType())
										.toList()));
	}
}
