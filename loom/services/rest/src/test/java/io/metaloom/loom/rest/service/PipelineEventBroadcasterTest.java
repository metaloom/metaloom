package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.metaloom.loom.rest.model.pipeline.event.PipelineEventMessage;
import io.metaloom.loom.rest.model.pipeline.event.PipelineEventType;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.vertx.core.http.ServerWebSocket;

/**
 * Fan-out of pipeline events to UI subscribers.
 *
 * <p>This used to be covered incidentally by the processor {@code PIPELINE_EVENT}
 * passthrough, which was removed because it bypassed the run aggregator. The
 * broadcaster itself is still the path every aggregator-produced frame takes, so it is
 * covered here directly rather than through a worker that no longer sends anything.</p>
 */
public class PipelineEventBroadcasterTest {

	private static ServerWebSocket openSocket() {
		ServerWebSocket ws = mock(ServerWebSocket.class);
		when(ws.isClosed()).thenReturn(false);
		when(ws.writeQueueFull()).thenReturn(false);
		return ws;
	}

	private static PipelineEventMessage nodeStats(String pipelineName, String runUuid, String nodeId) {
		return new PipelineEventMessage()
			.setType(PipelineEventType.NODE_STATS)
			.setPipelineName(pipelineName)
			.setPipelineRunUuid(runUuid)
			.setNodeId(nodeId)
			.setProcessedCount(42L);
	}

	@Test
	void testEveryUnfilteredSubscriberReceivesTheEvent() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		ServerWebSocket first = openSocket();
		ServerWebSocket second = openSocket();
		broadcaster.addSubscriber(first);
		broadcaster.addSubscriber(second);
		assertEquals(2, broadcaster.subscriberCount());

		broadcaster.broadcast(nodeStats("my-pipeline", UUID.randomUUID().toString(), "sha512"));

		for (ServerWebSocket ws : new ServerWebSocket[] { first, second }) {
			ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
			verify(ws, times(1)).writeTextMessage(sent.capture());
			String frame = sent.getValue();
			assertTrue(frame.contains("NODE_STATS"), "type must survive serialization: " + frame);
			assertTrue(frame.contains("sha512"), "nodeId must survive serialization: " + frame);
			assertTrue(frame.contains("42"), "counters must survive serialization: " + frame);
		}
	}

	/**
	 * Filtering by pipeline name still delivers every concurrent run of that pipeline,
	 * so a client following one run filters on the run uuid instead.
	 */
	@Test
	void testRunFilterSelectsASingleRun() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		String wanted = UUID.randomUUID().toString();
		String other = UUID.randomUUID().toString();
		ServerWebSocket subscriber = openSocket();
		broadcaster.addSubscriber(subscriber, null, wanted);

		broadcaster.broadcast(nodeStats("my-pipeline", other, "sha512"));
		verify(subscriber, never()).writeTextMessage(anyString());

		broadcaster.broadcast(nodeStats("my-pipeline", wanted, "sha512"));
		verify(subscriber, times(1)).writeTextMessage(anyString());
	}

	/**
	 * A run outlives the tab watching it. Broadcasting to a closed socket must prune it
	 * rather than write forever into a dead connection.
	 */
	@Test
	void testClosedSubscriberIsPrunedOnBroadcast() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		ServerWebSocket closed = mock(ServerWebSocket.class);
		when(closed.isClosed()).thenReturn(true);
		broadcaster.addSubscriber(closed);
		assertEquals(1, broadcaster.subscriberCount());

		broadcaster.broadcast(nodeStats("my-pipeline", UUID.randomUUID().toString(), "sha512"));

		verify(closed, never()).writeTextMessage(anyString());
		assertEquals(0, broadcaster.subscriberCount(), "a closed subscriber must be dropped on broadcast");
	}

	/**
	 * A subscriber too slow to drain its write queue loses the event instead of
	 * stalling the broadcaster thread.
	 */
	@Test
	void testFullWriteQueueDropsTheEventRatherThanBlocking() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		ServerWebSocket slow = mock(ServerWebSocket.class);
		when(slow.isClosed()).thenReturn(false);
		when(slow.writeQueueFull()).thenReturn(true);
		ServerWebSocket healthy = openSocket();
		broadcaster.addSubscriber(slow);
		broadcaster.addSubscriber(healthy);

		broadcaster.broadcast(nodeStats("my-pipeline", UUID.randomUUID().toString(), "sha512"));

		verify(slow, never()).writeTextMessage(anyString());
		verify(healthy, times(1)).writeTextMessage(anyString());
		assertEquals(2, broadcaster.subscriberCount(), "a slow subscriber is throttled, not evicted");
	}
}
