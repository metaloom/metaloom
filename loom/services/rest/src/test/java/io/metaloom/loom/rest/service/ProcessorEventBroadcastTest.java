package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventType;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.vertx.core.http.ServerWebSocket;

/**
 * Processor lifecycle events must reach UI subscribers over the single UI events
 * socket. These tests pin the two things the multiplexing gets wrong most easily:
 * the {@code channel} discriminator on the wire, and the fact that processor events
 * bypass the per-pipeline subscriber filter (they are fleet-wide, not pipeline-scoped)
 * while pipeline events still honour it.
 */
public class ProcessorEventBroadcastTest {

	private static ServerWebSocket openSocket() {
		ServerWebSocket ws = mock(ServerWebSocket.class);
		when(ws.isClosed()).thenReturn(false);
		when(ws.writeQueueFull()).thenReturn(false);
		return ws;
	}

	private static ProcessorRegistration registration(String nodeId) {
		return new ProcessorRegistration().setNodeId(nodeId).setName(nodeId).setPriority(1);
	}

	@Test
	void testStateChangeIsBroadcastWithProcessorChannel() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		ServerWebSocket subscriber = openSocket();
		broadcaster.addSubscriber(subscriber);

		// daos = null so register() skips persistence; broadcaster is wired.
		ProcessorRegistry registry = new ProcessorRegistry(null, broadcaster);
		registry.register("node-1", registration("node-1"), openSocket());
		registry.updateState("node-1", ProcessorState.OFFLINE);

		ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
		verify(subscriber, org.mockito.Mockito.atLeastOnce()).writeTextMessage(sent.capture());
		List<String> frames = sent.getAllValues();

		assertTrue(frames.stream().allMatch(f -> f.contains("\"channel\":\"PROCESSOR\"")),
			"every processor frame must carry the PROCESSOR channel discriminator");
		assertTrue(frames.stream().anyMatch(f -> f.contains("REGISTERED")), "register must emit REGISTERED");
		assertTrue(frames.stream().anyMatch(f -> f.contains("STATE_CHANGED") && f.contains("OFFLINE")),
			"updateState(OFFLINE) must emit STATE_CHANGED carrying the OFFLINE snapshot");
	}

	@Test
	void testProcessorEventsBypassThePipelineFilterButPipelineEventsDoNot() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		ServerWebSocket filtered = openSocket();
		broadcaster.addSubscriber(filtered, "pipeline-A"); // only wants pipeline-A events

		ProcessorRegistry registry = new ProcessorRegistry(null, broadcaster);
		registry.register("node-1", registration("node-1"), openSocket());

		// A filtered subscriber still receives fleet-wide processor events.
		verify(filtered, org.mockito.Mockito.atLeastOnce()).writeTextMessage(org.mockito.ArgumentMatchers.anyString());

		// ...but a pipeline event for a different pipeline must be filtered out.
		org.mockito.Mockito.reset(filtered);
		when(filtered.isClosed()).thenReturn(false);
		when(filtered.writeQueueFull()).thenReturn(false);
		broadcaster.broadcast(new PipelineEventMessage(PipelineEventType.NODE_STARTED, "pipeline-B"));
		verify(filtered, never()).writeTextMessage(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void testRegistryWithoutBroadcasterDoesNotThrow() {
		// The no-arg constructor (used by unit tests) has no broadcaster; every emit is guarded.
		ProcessorRegistry registry = new ProcessorRegistry();
		assertDoesNotThrow(() -> {
			registry.register("node-1", registration("node-1"), openSocket());
			registry.updateState("node-1", ProcessorState.PAUSED);
			registry.heartbeat("node-1");
			registry.unregister("node-1");
		});
		assertEquals(0, registry.getAll().size());
	}
}
