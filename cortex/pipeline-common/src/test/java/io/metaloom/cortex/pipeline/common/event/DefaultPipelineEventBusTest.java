package io.metaloom.cortex.pipeline.common.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.common.StubMedia;

/**
 * The in-process event bus. This module had no test directory at all before this file.
 *
 * <p>
 * Two properties here are load-bearing rather than incidental. Dispatch is <em>synchronous on the
 * publishing thread</em>, which is what lets a node's caller observe the effects of a listener the
 * moment {@code publish} returns - so a listener that throws would take the publisher down with it
 * if the bus did not isolate it. And the two channels are separate: completion events carry the full
 * media handle and result for internal coordination, tracking events carry scalars for
 * observability, and a subscriber to one must never be fed the other.
 * </p>
 */
public class DefaultPipelineEventBusTest {

	private final DefaultPipelineEventBus bus = new DefaultPipelineEventBus();

	private static NodeCompletionEvent completion(String nodeId) {
		return new NodeCompletionEvent(nodeId, new StubMedia("/media/" + nodeId + ".mp4"),
			new NodeResult(ResultState.SUCCESS));
	}

	private static PipelineTrackingEvent tracking(String nodeId) {
		return new PipelineTrackingEvent(PipelineTrackingEvent.Type.NODE_COMPLETED, "demo", nodeId, "/media/a.mp4");
	}

	private static List<String> ids(List<NodeCompletionEvent> events) {
		List<String> ids = new ArrayList<>();
		events.forEach(event -> ids.add(event.getNodeId()));
		return ids;
	}

	// ── Routing ───────────────────────────────────────────────────────────

	@Test
	void testANodeSubscriberOnlyHearsAboutItsOwnNode() {
		List<NodeCompletionEvent> sha = new ArrayList<>();
		bus.subscribe("sha512", sha::add);

		bus.publish(completion("sha512"));
		bus.publish(completion("md5"));

		assertEquals(List.of("sha512"), ids(sha));
	}

	@Test
	void testAGlobalSubscriberHearsEveryNodeInPublishOrder() {
		List<NodeCompletionEvent> all = new ArrayList<>();
		bus.subscribeAll(all::add);

		bus.publish(completion("sha512"));
		bus.publish(completion("md5"));

		assertEquals(List.of("sha512", "md5"), ids(all));
	}

	@Test
	void testANodeSubscriberAndAGlobalOneBothGetTheSameEvent() {
		List<NodeCompletionEvent> scoped = new ArrayList<>();
		List<NodeCompletionEvent> all = new ArrayList<>();
		bus.subscribe("sha512", scoped::add);
		bus.subscribeAll(all::add);

		NodeCompletionEvent event = completion("sha512");
		bus.publish(event);

		assertEquals(List.of(event), scoped);
		assertEquals(List.of(event), all);
	}

	@Test
	void testTwoSubscribersToTheSameNodeAreBothCalled() {
		// The node channel is a list, not a slot - registering a second listener must not replace the
		// first, which is how a later subscriber silently disables an earlier one.
		List<NodeCompletionEvent> first = new ArrayList<>();
		List<NodeCompletionEvent> second = new ArrayList<>();
		bus.subscribe("sha512", first::add);
		bus.subscribe("sha512", second::add);

		bus.publish(completion("sha512"));

		assertEquals(1, first.size());
		assertEquals(1, second.size());
	}

	@Test
	void testPublishingWithNoSubscribersIsANoOp() {
		assertDoesNotThrow(() -> bus.publish(completion("sha512")));
		assertDoesNotThrow(() -> bus.publishTracking(tracking("sha512")));
	}

	// ── The two channels are separate ─────────────────────────────────────

	@Test
	void testTrackingAndCompletionAreSeparateChannels() {
		List<NodeCompletionEvent> completions = new ArrayList<>();
		List<PipelineTrackingEvent> trackings = new ArrayList<>();
		bus.subscribeAll(completions::add);
		bus.subscribeTracking(trackings::add);

		bus.publish(completion("sha512"));
		assertEquals(1, completions.size());
		assertEquals(0, trackings.size(), "A completion is not a tracking event");

		bus.publishTracking(tracking("sha512"));
		assertEquals(1, completions.size(), "A tracking event must not reach the completion subscribers");
		assertEquals(1, trackings.size());
	}

	// ── Isolation ─────────────────────────────────────────────────────────

	/**
	 * Dispatch is synchronous on the publishing thread, so an unguarded listener exception would
	 * propagate into whichever node happened to publish - failing that node because of a fault in an
	 * observer of it. Every listener is therefore called even when an earlier one throws.
	 */
	@Test
	void testAThrowingListenerDoesNotStopTheOthersOrThePublisher() {
		List<NodeCompletionEvent> beforeThrower = new ArrayList<>();
		List<NodeCompletionEvent> afterThrower = new ArrayList<>();

		bus.subscribe("sha512", beforeThrower::add);
		bus.subscribe("sha512", event -> {
			throw new IllegalStateException("listener is broken");
		});
		bus.subscribe("sha512", afterThrower::add);
		bus.subscribeAll(event -> {
			throw new IllegalStateException("global listener is broken");
		});
		List<NodeCompletionEvent> global = new ArrayList<>();
		bus.subscribeAll(global::add);

		assertDoesNotThrow(() -> bus.publish(completion("sha512")));

		assertEquals(1, beforeThrower.size());
		assertEquals(1, afterThrower.size(), "A listener registered after a broken one must still be called");
		assertEquals(1, global.size(), "and the global channel must survive its own broken listener");
	}

	@Test
	void testAThrowingTrackingListenerIsIsolatedToo() {
		List<PipelineTrackingEvent> survivor = new ArrayList<>();
		bus.subscribeTracking(event -> {
			throw new IllegalStateException("broken");
		});
		bus.subscribeTracking(survivor::add);

		assertDoesNotThrow(() -> bus.publishTracking(tracking("sha512")));
		assertEquals(1, survivor.size());
	}

	// ── Unsubscribing ─────────────────────────────────────────────────────

	@Test
	void testUnsubscribeStopsDeliveryToThatListenerOnly() {
		List<NodeCompletionEvent> leaving = new ArrayList<>();
		List<NodeCompletionEvent> staying = new ArrayList<>();
		String handle = bus.subscribe("sha512", leaving::add);
		bus.subscribe("sha512", staying::add);

		bus.publish(completion("sha512"));
		bus.unsubscribe(handle);
		bus.publish(completion("sha512"));

		assertEquals(1, leaving.size(), "The unsubscribed listener must not see the second event");
		assertEquals(2, staying.size(), "and its neighbour must be untouched");
	}

	@Test
	void testEveryChannelCanBeUnsubscribed() {
		List<NodeCompletionEvent> all = new ArrayList<>();
		List<PipelineTrackingEvent> tracked = new ArrayList<>();
		String allHandle = bus.subscribeAll(all::add);
		String trackingHandle = bus.subscribeTracking(tracked::add);

		bus.unsubscribe(allHandle);
		bus.unsubscribe(trackingHandle);
		bus.publish(completion("sha512"));
		bus.publishTracking(tracking("sha512"));

		assertTrue(all.isEmpty());
		assertTrue(tracked.isEmpty());
	}

	@Test
	void testHandlesAreDistinctAndUnknownOnesAreIgnored() {
		String first = bus.subscribeAll(event -> {
		});
		String second = bus.subscribeAll(event -> {
		});
		assertNotEquals(first, second, "Two subscriptions must not share a handle - unsubscribing one would kill both");

		assertDoesNotThrow(() -> bus.unsubscribe("no-such-handle"));
		// Unsubscribing twice is what a listener with its own retry or shutdown path will do.
		bus.unsubscribe(first);
		assertDoesNotThrow(() -> bus.unsubscribe(first));
	}

	@Test
	void testClearRemovesEverySubscription() {
		List<NodeCompletionEvent> scoped = new ArrayList<>();
		List<NodeCompletionEvent> all = new ArrayList<>();
		List<PipelineTrackingEvent> tracked = new ArrayList<>();
		bus.subscribe("sha512", scoped::add);
		bus.subscribeAll(all::add);
		bus.subscribeTracking(tracked::add);

		bus.clear();
		bus.publish(completion("sha512"));
		bus.publishTracking(tracking("sha512"));

		assertTrue(scoped.isEmpty());
		assertTrue(all.isEmpty());
		assertTrue(tracked.isEmpty());
	}
}
