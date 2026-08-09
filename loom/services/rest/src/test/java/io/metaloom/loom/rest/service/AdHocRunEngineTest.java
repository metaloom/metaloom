package io.metaloom.loom.rest.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.pipeline.engine.NodeDispatcher;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.engine.RunStateStore;
import io.metaloom.loom.pipeline.engine.RunSummary;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.rest.service.impl.AdHocGraphBuilder;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * An ad-hoc graph executed the way {@code NodeRunService} executes one: fed by Loom, not by a worker.
 *
 * <p>
 * The claim under test is the load-bearing one for the whole feature - that a run whose source is
 * {@code loom-fetch} needs no worker to enumerate anything. If the engine ever dispatched that node,
 * every ad-hoc run would fail the moment no worker advertised a source kind, which is the normal case.
 * </p>
 *
 * <p>
 * No database and no transport: results are handed to the engine the way {@code ProcessorEndpoint}
 * hands them over after decoding a frame. What is under test is the orchestration.
 * </p>
 */
public class AdHocRunEngineTest {

	/** Records what was dispatched and answers on demand, standing in for Cortex. */
	private static class RecordingWorker implements NodeDispatcher {

		private final List<NodeTask> inbox = new ArrayList<>();

		@Override
		public String dispatch(NodeTask task) {
			inbox.add(task);
			return "recording-worker";
		}

		NodeTask take(String nodeId) {
			return inbox.stream().filter(t -> t.getNodeId().equals(nodeId)).findFirst()
				.orElseThrow(() -> new AssertionError("No task dispatched for '" + nodeId
					+ "'. Dispatched: " + inbox.stream().map(NodeTask::getNodeId).toList()));
		}

		List<String> dispatchedNodeIds() {
			return inbox.stream().map(NodeTask::getNodeId).toList();
		}
	}

	/** The two-node graph AdHocGraphBuilder produces for {@code sha512 -> vlm}. */
	private static JsonObject twoNodeDefinition() {
		return AdHocGraphBuilder.withLoomFetchSource(new JsonObject()
			.put("version", 1)
			.put("name", "describe")
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "sha512").put("type", "sha512"))
				.add(new JsonObject().put("id", "vlm").put("type", "vlm")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "e1")
					.put("source", "sha512").put("sourcePort", "hash")
					.put("target", "vlm").put("targetPort", "media"))));
	}

	@Test
	public void testLoomFetchIsNeverDispatchedAndItsMediaStillFeedsTheGraph() {
		RecordingWorker worker = new RecordingWorker();
		PipelineGraph graph = new PipelineGraphParser().parse("describe", twoNodeDefinition(), true, false, 0);
		PipelineRunEngine engine = new PipelineRunEngine(graph, worker, UUID.randomUUID(), RunStateStore.NOOP);

		AtomicReference<RunSummary> completed = new AtomicReference<>();
		engine.onCompletion(completed::set);

		engine.start();
		// This is the whole trick: Loom knows the path already, so it plays the part the source node
		// would otherwise have played on a worker.
		String itemId = engine.onItemDiscovered(new MediaRef("/data/beach.jpg", "abc", 1024, MediaRef.IMAGE));
		engine.onSourceComplete(1);

		assertThat(worker.dispatchedNodeIds())
			.as("the Loom-side source must never leave for a worker")
			.doesNotContain(AdHocGraphBuilder.SOURCE_NODE_ID);

		NodeTask first = worker.take("sha512");
		assertThat(first.getMedia().getPath()).isEqualTo("/data/beach.jpg");

		assertThat(worker.dispatchedNodeIds())
			.as("a downstream node must wait for its input")
			.doesNotContain("vlm");

		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(first.getTaskUuid(), "sha512", 5,
			Map.of("hash", PortPayload.one(ContentTypeRegistry.HASH_SHA512, Origin.single(itemId), "abc"))));

		NodeTask second = worker.take("vlm");
		assertThat(second.getInputs()).containsKey("media");

		engine.onNodeTaskResult(itemId, NodeTaskResult.completed(second.getTaskUuid(), "vlm", 5,
			Map.of("text", PortPayload.one(ContentTypeRegistry.TEXT_PLAIN, Origin.single(itemId), "A beach at sunset."))));

		assertThat(completed.get()).as("the run must reach a terminal state").isNotNull();
		assertThat(completed.get().getSuccessCount()).isEqualTo(1);

		// Both outputs stay readable off the engine, which is what the synchronous probe reads.
		Map<String, NodeTaskResult> results = engine.getItem(itemId).getResults();
		assertThat(results.get("sha512").getOutputs()).containsKey("hash");
		assertThat(results.get("vlm").getOutputs().get("text").single()).isEqualTo("A beach at sunset.");
	}

	@Test
	public void testEveryItemGetsItsOwnExecutionOfTheGraph() {
		RecordingWorker worker = new RecordingWorker();
		JsonObject definition = AdHocGraphBuilder.singleNodeDefinition("sha512", Map.of());
		PipelineGraph graph = new PipelineGraphParser().parse("probe sha512", definition, true, false, 0);
		PipelineRunEngine engine = new PipelineRunEngine(graph, worker, UUID.randomUUID(), RunStateStore.NOOP);

		engine.start();
		List<String> itemIds = new ArrayList<>();
		itemIds.add(engine.onItemDiscovered(new MediaRef("/data/a.jpg", "a", 1, MediaRef.IMAGE)));
		itemIds.add(engine.onItemDiscovered(new MediaRef("/data/b.jpg", "b", 1, MediaRef.IMAGE)));
		engine.onSourceComplete(2);

		assertThat(worker.dispatchedNodeIds()).containsExactly("sha512", "sha512");
		assertThat(worker.inbox.stream().map(t -> t.getMedia().getPath()).toList())
			.containsExactlyInAnyOrder("/data/a.jpg", "/data/b.jpg");
		assertThat(itemIds).doesNotHaveDuplicates();
	}

}
