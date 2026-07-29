package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeState;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.service.impl.WebSocketNodeDispatcher;

/**
 * Tests for the production {@link io.metaloom.loom.pipeline.engine.NodeDispatcher}.
 *
 * <p>Focused on the failure path, because that is the one with teeth: if dispatch
 * quietly returns success when nothing was sent, the engine waits forever for a
 * result that cannot arrive and the run never terminates.</p>
 */
public class WebSocketNodeDispatcherTest {

	private static NodeTask task() {
		return new NodeTask(UUID.randomUUID(), UUID.randomUUID(), "item-1", "hash", "sha512",
			MediaRef.of("/media/a.mp4"), Map.of(), Map.of());
	}

	@Test
	void testDispatchFailsWhenNoProcessorIsConnected() {
		WebSocketNodeDispatcher dispatcher = new WebSocketNodeDispatcher(new ProcessorRegistry());

		assertNull(dispatcher.dispatch(task()),
			"With no worker connected the dispatcher must report failure so the engine "
				+ "can settle the node rather than stall the run");
	}

	@Test
	void testEngineSettlesTheRunWhenDispatchAlwaysFails() {
		// The behaviour that matters end to end: an undispatchable pipeline still
		// reaches a terminal state instead of hanging.
		ProcessorRegistry emptyRegistry = new ProcessorRegistry();
		WebSocketNodeDispatcher dispatcher = new WebSocketNodeDispatcher(emptyRegistry);

		io.vertx.core.json.JsonObject definition = new io.vertx.core.json.JsonObject()
			.put("nodes", new io.vertx.core.json.JsonArray()
				.add(new io.vertx.core.json.JsonObject().put("id", "src")
					.put("type", "filesystem-source").put("source", true))
				.add(new io.vertx.core.json.JsonObject().put("id", "hash").put("type", "sha512")))
			.put("edges", new io.vertx.core.json.JsonArray()
				.add(new io.vertx.core.json.JsonObject()
					.put("source", "src").put("sourcePort", "media")
					.put("target", "hash").put("targetPort", "media")));

		io.metaloom.loom.pipeline.graph.PipelineGraph graph =
			new io.metaloom.loom.pipeline.graph.PipelineGraphParser()
				.parse("no-workers", definition, true, false, 0);

		io.metaloom.loom.pipeline.engine.PipelineRunEngine engine =
			new io.metaloom.loom.pipeline.engine.PipelineRunEngine(graph, dispatcher, UUID.randomUUID());

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		engine.onSourceComplete(1);

		assertTrue(engine.isComplete(), "The run must terminate even though nothing could be dispatched");
		assertEquals(NodeState.FAILED, engine.getItem(itemId).getResults().get("hash").getState());
		assertEquals("FAILED", engine.summary().getStatus());
	}
}
