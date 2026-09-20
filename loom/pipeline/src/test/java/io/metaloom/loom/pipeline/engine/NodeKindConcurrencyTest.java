package io.metaloom.loom.pipeline.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ServiceLoader;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorProvider;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A node's declared concurrency actually reaches the engine.
 *
 * <p>
 * {@link PipelineRunEngineBulkheadTest} covers the ceiling once it is set. This covers the thing
 * that was missing for much longer: anybody setting it. See {@link NodeKindConcurrency} for the
 * failure that went with the gap — a GPU worker aborted by a sixth simultaneous whisper context.
 * </p>
 */
public class NodeKindConcurrencyTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	/** The registry as the server builds it: every built-in descriptor, from the ServiceLoader. */
	private NodeDescriptorRegistry builtinRegistry() {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		ServiceLoader.load(NodeDescriptorProvider.class)
			.forEach(provider -> provider.getDescriptors().forEach(registry::register));
		return registry;
	}

	/** One slow GPU kind and one cheap one, both fed by the same source. */
	private PipelineGraph graph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "speech").put("type", "whisper"))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "speech").put("targetPort", "video"))
				.add(new JsonObject().put("source", "src").put("sourcePort", "media").put("target", "hash").put("targetPort", "media")));
		return parser.parse("concurrency", definition, true, false, 0);
	}

	private static long dispatchedTo(FakeNodeDispatcher dispatcher, String nodeId) {
		return dispatcher.dispatched().stream().filter(t -> t.getNodeId().equals(nodeId)).count();
	}

	@Test
	void testWhisperDeclaresOneAtATime() {
		NodeDescriptorRegistry registry = builtinRegistry();
		NodeDescriptor whisper = registry.get("whisper");
		// If this ever rises, a single-GPU worker stops being protected by the wiring below — so
		// the number is asserted here rather than only assumed by the next test.
		assertEquals(1, whisper.getDefaultConcurrency(), "whisper transcribes one media item at a time");
		assertTrue(registry.get("sha512").getDefaultConcurrency() > 1, "hashing is not the bottleneck");
	}

	@Test
	void testTheCeilingReachesTheEngine() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineGraph graph = graph();
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		NodeKindConcurrency.apply(graph, engine, builtinRegistry());
		engine.start();

		// One source batch carrying several items: the shape that took the worker down, and the
		// shape a differential scan never produces, which is why nothing caught it for so long.
		for (int i = 0; i < 6; i++) {
			engine.onItemDiscovered(MediaRef.of("/content/episode-" + i + ".mkv"));
		}

		assertEquals(1, dispatchedTo(dispatcher, "speech"),
			"whisper declares defaultConcurrency=1, so only one task may be outstanding");
		// Per kind and from each kind's own descriptor, not one shared throttle: hashing declares 4
		// and gets 4 out of the same six items that the GPU kind is allowed one of.
		int hashCeiling = builtinRegistry().get("sha512").getDefaultConcurrency();
		assertEquals(hashCeiling, dispatchedTo(dispatcher, "hash"), "sha512 runs at its own ceiling, not whisper's");
		assertTrue(hashCeiling > 1, "and that ceiling is genuinely higher than the GPU kind's");
	}

	@Test
	void testWithoutADescriptorNothingIsCapped() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineGraph graph = graph();
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		// An empty registry is what a fork carrying unannounced nodes looks like. Capping an unknown
		// kind to zero would stall the run outright, which is far worse than not capping it.
		NodeKindConcurrency.apply(graph, engine, new NodeDescriptorRegistry());
		engine.start();

		for (int i = 0; i < 3; i++) {
			engine.onItemDiscovered(MediaRef.of("/content/episode-" + i + ".mkv"));
		}

		assertEquals(3, dispatchedTo(dispatcher, "speech"), "an unknown kind runs uncapped rather than not at all");
	}

	@Test
	void testANullRegistryIsTolerated() {
		FakeNodeDispatcher dispatcher = new FakeNodeDispatcher();
		PipelineGraph graph = graph();
		PipelineRunEngine engine = new PipelineRunEngine(graph, dispatcher, UUID.randomUUID());
		NodeKindConcurrency.apply(graph, engine, null);
		engine.start();
		engine.onItemDiscovered(MediaRef.of("/content/episode.mkv"));
		assertEquals(1, dispatchedTo(dispatcher, "speech"));
	}
}
