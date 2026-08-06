package io.metaloom.loom.pipeline.engine;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_ANY;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.TEXT_ANY;
import static io.metaloom.loom.pipeline.engine.Payloads.outputs;
import static io.metaloom.loom.pipeline.engine.Payloads.payload;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.pipeline.TestDescriptors;
import io.metaloom.loom.pipeline.graph.GraphValidationException;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A MIME-bucketed {@code filter} graph, end to end through the engine.
 *
 * <p>
 * {@code PipelineRunEnginePortRoutingTest} proves the routing rule against a synthetic two-port
 * {@code router}. This proves the thing an author actually builds: the real {@code filter} kind, its
 * ports <em>derived from the buckets they typed</em> by {@code FilterPortResolver}, and two files of
 * different types going down two different branches of one graph. Nothing here would be exercised by
 * the node's own unit tests — those end at "the node wrote this port and stayed silent on that one",
 * and the silence only becomes a skip out here.
 * </p>
 *
 * <p>
 * The {@code filter} descriptor is registered locally rather than added to {@link TestDescriptors},
 * whose vocabulary is deliberately synthetic. The <em>resolver</em> is the real one, discovered by
 * {@code ServiceLoader} — a hand-written stand-in would let the bucket ids and the port ids drift
 * apart, which is exactly the failure this is here to catch.
 * </p>
 */
public class PipelineRunEngineFilterRoutingTest {

	private final PipelineGraphParser parser = new PipelineGraphParser(registry());

	private static NodeDescriptorRegistry registry() {
		NodeDescriptorRegistry registry = TestDescriptors.registry();
		registry.register(new NodeDescriptor().setNodeId("filter").setName("Filter")
			.setCategory(NodeCategory.FILTER)
			.setDynamicPorts(true)
			.setInputPorts(List.of(PortSpec.one("media", MEDIA_ANY), PortSpec.optionalOne("text", TEXT_ANY)))
			// Deliberately empty: with dynamicPorts the resolver supplies every output, and a static
			// list here would mask a resolver that produced nothing.
			.setOutputPorts(List.of()));
		return registry;
	}

	/**
	 * src → f(filter, buckets: pictures=image/*, clips=video/*); f.pictures → pic, f.clips → clip.
	 *
	 * <p>
	 * Neither branch is wired to {@code other}, which is the ordinary shape: an author routes the two
	 * kinds they care about and lets everything else fall off the end of the graph.
	 * </p>
	 */
	private PipelineGraph mimeBucketed() {
		JsonObject filter = new JsonObject().put("id", "f").put("type", "filter")
			.put("options", new JsonObject()
				.put("filterBy", "MIME")
				.put("buckets", new JsonArray()
					.add(new JsonObject().put("id", "pictures").put("label", "Pictures").put("match", "image/*"))
					.add(new JsonObject().put("id", "clips").put("label", "Clips").put("match", "video/*"))));

		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "test-source").put("source", true))
				.add(filter)
				.add(new JsonObject().put("id", "pic").put("type", "describe"))
				.add(new JsonObject().put("id", "clip").put("type", "describe")))
			.put("edges", new JsonArray()
				.add(edge("src", "media", "f", "media"))
				.add(edge("f", "pictures", "pic", "media"))
				.add(edge("f", "clips", "clip", "media")));

		// Round-tripped through text on purpose: a pipeline definition arrives as JSON, and the
		// resolver reads the options as a plain Map/List. Handing it live JsonObject instances would
		// test a shape production never sees.
		return parser.parse("mime-bucketed", new JsonObject(definition.encode()), true, false, 0);
	}

	private static JsonObject edge(String from, String sourcePort, String to, String targetPort) {
		return new JsonObject().put("source", from).put("sourcePort", sourcePort)
			.put("target", to).put("targetPort", targetPort);
	}

	private static NodeTaskResult routed(NodeTask task, String bucketPort, String path) {
		return NodeTaskResult.completed(task.getTaskUuid(), task.getNodeId(), 5,
			outputs(bucketPort, payload(MEDIA_ANY, path)));
	}

	private static boolean dispatchedFor(FakeNodeDispatcher dispatcher, String nodeId, String itemId) {
		return dispatcher.dispatched().stream()
			.anyMatch(t -> t.getNodeId().equals(nodeId) && itemId.equals(t.getItemId()));
	}

	/**
	 * The buckets an author typed become the ports an author can wire — and nothing else does. A
	 * graph wired to a bucket that was never configured must be refused while its author is looking
	 * at it, not halfway through a run.
	 */
	@Test
	void testOnlyTheConfiguredBucketsAreWirablePorts() {
		List<String> ports = registry()
			.resolvePorts("filter", mimeBucketed().getNode("f").getOptions())
			.outputs().stream().map(PortSpec::getId).toList();
		assertEquals(List.of("pictures", "clips", "other", "passed", "bucket"), ports);

		GraphValidationException e = assertThrows(GraphValidationException.class, () -> parser.parse("typo",
			new JsonObject(new JsonObject()
				.put("nodes", new JsonArray()
					.add(new JsonObject().put("id", "src").put("type", "test-source").put("source", true))
					.add(new JsonObject().put("id", "f").put("type", "filter")
						.put("options", new JsonObject().put("filterBy", "MIME")
							.put("buckets", new JsonArray().add(new JsonObject().put("id", "pictures").put("match", "image/*")))))
					.add(new JsonObject().put("id", "clip").put("type", "describe")))
				.put("edges", new JsonArray()
					.add(edge("src", "media", "f", "media"))
					.add(edge("f", "clips", "clip", "media")))
				.encode()),
			true, false, 0));
		assertTrue(e.getMessage().contains("clips"), e.getMessage());
	}

	/**
	 * Two files, one graph, two branches. This is the capability the eight deleted {@code filter-*}
	 * kinds had and the consolidation lost: routing by file type without an LLM round trip.
	 */
	@Test
	void testTwoFileTypesGoDownTwoDifferentBranches() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(mimeBucketed(), dispatcher, UUID.randomUUID());

		engine.start();
		String image = engine.onItemDiscovered(MediaRef.of("/media/holiday.png"));
		String video = engine.onItemDiscovered(MediaRef.of("/media/clip.mp4"));
		engine.onSourceComplete(2);

		// The node writes exactly one bucket port per item and stays silent on the other.
		engine.onNodeTaskResult(image, routed(dispatcher.taskFor("f", image), "pictures", "/media/holiday.png"));
		engine.onNodeTaskResult(video, routed(dispatcher.taskFor("f", video), "clips", "/media/clip.mp4"));

		assertTrue(dispatchedFor(dispatcher, "pic", image), "the image must run down the 'pictures' branch");
		assertFalse(dispatchedFor(dispatcher, "clip", image), "and must not also run down 'clips'");

		assertTrue(dispatchedFor(dispatcher, "clip", video), "the video must run down the 'clips' branch");
		assertFalse(dispatchedFor(dispatcher, "pic", video), "and must not also run down 'pictures'");

		assertEquals(NodeState.SKIPPED, engine.getItem(image).getResults().get("clip").getState());
		assertEquals(NodeState.SKIPPED, engine.getItem(video).getResults().get("pic").getState());
	}

	/**
	 * A type no bucket matched writes only {@code other}, which nothing is wired to. Both branches
	 * close and the run must still finish rather than waiting on tasks that will never be dispatched.
	 */
	@Test
	void testAnUnmatchedFileClosesBothBranchesAndTheRunStillCompletes() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineRunEngine engine = new PipelineRunEngine(mimeBucketed(), dispatcher, UUID.randomUUID());

		engine.start();
		String document = engine.onItemDiscovered(MediaRef.of("/media/notes.pdf"));
		engine.onSourceComplete(1);

		engine.onNodeTaskResult(document, routed(dispatcher.taskFor("f", document), "other", "/media/notes.pdf"));

		assertFalse(dispatcher.wasDispatched("pic"));
		assertFalse(dispatcher.wasDispatched("clip"));
		assertTrue(engine.isComplete(), "a run whose every branch closed must still finish");
	}
}
