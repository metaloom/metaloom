package io.metaloom.loom.rest.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.notification.NotificationType;
import io.metaloom.loom.rest.model.notification.NotificationEventMessage;
import io.metaloom.loom.rest.model.notification.NotificationResponse;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.vertx.core.http.ServerWebSocket;

/**
 * Per-user addressing of the {@code NOTIFICATION} channel.
 *
 * <p>
 * Unlike the processor and node-registry channels on the same socket, this one is addressed to exactly one user — so the tests that matter here are
 * the ones asserting who does <b>not</b> get a frame. The default deployment runs with {@code LOOM_WS_STRICT_AUTH=false}, which accepts a socket
 * with no token at all; if such a subscriber matched a recipient, every user's inbox would stream to any anonymous connection.
 * </p>
 */
public class NotificationBroadcastTest {

	private static ServerWebSocket openSocket() {
		ServerWebSocket ws = mock(ServerWebSocket.class);
		when(ws.isClosed()).thenReturn(false);
		when(ws.writeQueueFull()).thenReturn(false);
		return ws;
	}

	private static NotificationEventMessage event() {
		return new NotificationEventMessage()
			.setType(NotificationType.TASK_ASSIGNED)
			.setNotification(new NotificationResponse().setTitle("someone assigned you a task"))
			.setUnreadCount(3);
	}

	@Test
	void testOnlyTheRecipientsSocketReceivesTheFrame() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		UUID alice = UUID.randomUUID();
		UUID bob = UUID.randomUUID();

		ServerWebSocket aliceSocket = openSocket();
		ServerWebSocket bobSocket = openSocket();
		broadcaster.addSubscriber(aliceSocket, null, null, alice);
		broadcaster.addSubscriber(bobSocket, null, null, bob);

		broadcaster.broadcastNotification(alice, event());

		verify(aliceSocket, times(1)).writeTextMessage(anyString());
		verify(bobSocket, never()).writeTextMessage(anyString());
	}

	@Test
	void testATokenlessSubscriberReceivesNothing() {
		// THE fail-closed assertion. Lenient auth is the DEFAULT, so a socket opened without
		// ?token= is accepted and arrives with a null user. It must never match a recipient.
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		UUID alice = UUID.randomUUID();

		ServerWebSocket anonymousSocket = openSocket();
		broadcaster.addSubscriber(anonymousSocket, null, null, null);

		broadcaster.broadcastNotification(alice, event());

		verify(anonymousSocket, never()).writeTextMessage(anyString());
	}

	@Test
	void testTheLegacyOverloadsProduceTokenlessSubscribers() {
		// Every pre-existing call site uses the 1-, 2- or 3-arg overload. Those must resolve to
		// a null user rather than, say, an unset field that accidentally matches.
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		ServerWebSocket oneArg = openSocket();
		ServerWebSocket threeArg = openSocket();
		broadcaster.addSubscriber(oneArg);
		broadcaster.addSubscriber(threeArg, "some-pipeline", null);

		broadcaster.broadcastNotification(UUID.randomUUID(), event());

		verify(oneArg, never()).writeTextMessage(anyString());
		verify(threeArg, never()).writeTextMessage(anyString());
	}

	@Test
	void testANullRecipientReachesNobody() {
		// The second fail-closed rule: a dispatch bug that loses the recipient must send the
		// frame to nobody, not to everybody.
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		ServerWebSocket socket = openSocket();
		broadcaster.addSubscriber(socket, null, null, UUID.randomUUID());

		broadcaster.broadcastNotification(null, event());

		verify(socket, never()).writeTextMessage(anyString());
	}

	@Test
	void testAllOfOneUsersSocketsReceiveTheFrame() {
		// One person, two browser tabs. Both are subscribers of the same user.
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		UUID alice = UUID.randomUUID();
		ServerWebSocket tabOne = openSocket();
		ServerWebSocket tabTwo = openSocket();
		broadcaster.addSubscriber(tabOne, null, null, alice);
		broadcaster.addSubscriber(tabTwo, null, null, alice);

		broadcaster.broadcastNotification(alice, event());

		verify(tabOne, times(1)).writeTextMessage(anyString());
		verify(tabTwo, times(1)).writeTextMessage(anyString());
	}

	@Test
	void testAPipelineFilterDoesNotSuppressNotifications() {
		// A client watching one pipeline still has an inbox. The ?pipeline= filter applies to
		// pipeline frames only.
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		UUID alice = UUID.randomUUID();
		ServerWebSocket filtered = openSocket();
		broadcaster.addSubscriber(filtered, "some-other-pipeline", null, alice);

		broadcaster.broadcastNotification(alice, event());

		verify(filtered, times(1)).writeTextMessage(anyString());
	}

	@Test
	void testAClosedSocketIsPrunedRatherThanWritten() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		UUID alice = UUID.randomUUID();
		ServerWebSocket closed = mock(ServerWebSocket.class);
		when(closed.isClosed()).thenReturn(true);
		broadcaster.addSubscriber(closed, null, null, alice);

		broadcaster.broadcastNotification(alice, event());

		verify(closed, never()).writeTextMessage(anyString());
		org.junit.jupiter.api.Assertions.assertEquals(0, broadcaster.subscriberCount(),
			"A closed socket is pruned during the broadcast");
	}

}
