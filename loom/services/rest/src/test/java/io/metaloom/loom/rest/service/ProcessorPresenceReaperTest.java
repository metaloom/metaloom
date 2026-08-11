package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.metaloom.loom.api.pipeline.NodeTaskState;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.pipeline.engine.NodeDispatcher;
import io.metaloom.loom.pipeline.engine.PipelineRunEngine;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTask;
import io.metaloom.loom.rest.model.nodes.NodeAvailability;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.service.impl.LeaseReaper;
import io.metaloom.loom.rest.service.impl.NodeAvailabilityService;
import io.metaloom.loom.rest.service.impl.NodeRegistrationService;
import io.metaloom.loom.rest.service.impl.PipelineEventBroadcaster;
import io.metaloom.loom.rest.service.impl.PipelineRunRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorPresenceReaper;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Expiring a worker that stopped heartbeating.
 *
 * <p>Presence used to be write-only: every message stamped {@code lastSeen} and nothing
 * ever read it back. The only route to {@code OFFLINE} was the socket close handler, which
 * a half-open connection never fires — so the tests here are all about the case where
 * nothing closes: the worker has to stop being placeable, stop being offered to the editor,
 * and give its work back, all without a socket event.</p>
 */
public class ProcessorPresenceReaperTest {

	/** A sweep tolerance short enough to reason about, long enough not to race the clock. */
	private static final long HEARTBEAT_MS = 1_000;
	private static final int MISSED = 3;

	private static ServerWebSocket openSocket() {
		ServerWebSocket ws = mock(ServerWebSocket.class);
		when(ws.isClosed()).thenReturn(false);
		when(ws.writeQueueFull()).thenReturn(false);
		return ws;
	}

	private static ProcessorRegistration registration(String nodeId) {
		return new ProcessorRegistration().setNodeId(nodeId).setName(nodeId).setPriority(1)
			.setCapabilities(Set.of(ProcessorCapability.CPU));
	}

	private static ProcessorPresenceReaper reaper(ProcessorRegistry registry, LeaseReaper leaseReaper) {
		return new ProcessorPresenceReaper(registry, leaseReaper, true, HEARTBEAT_MS, MISSED);
	}

	/** Push a worker's last contact far enough into the past that the next sweep expires it. */
	private static void silenceSince(ProcessorRegistry registry, String nodeId, Duration ago) {
		ConnectedProcessor processor = registry.get(nodeId);
		assertNotNull(processor, "cannot silence a worker that never registered");
		processor.lastSeen = Instant.now().minus(ago);
	}

	// ── Eviction ─────────────────────────────────────────────────────────────────────────────────

	@Test
	public void shouldEvictAWorkerThatStoppedHeartbeating() {
		PipelineEventBroadcaster broadcaster = new PipelineEventBroadcaster();
		ServerWebSocket subscriber = openSocket();
		broadcaster.addSubscriber(subscriber);

		ProcessorRegistry registry = new ProcessorRegistry(null, broadcaster);
		registry.register("node-1", registration("node-1"), openSocket());
		silenceSince(registry, "node-1", Duration.ofMinutes(5));

		assertEquals(1, reaper(registry, null).sweep(Instant.now(), 100));

		// The point of the whole mechanism: a worker nothing has heard from stops being chosen.
		assertNull(registry.selectProcessor(ProcessorCapability.CPU),
			"an expired worker must not win a placement decision");
		assertNull(registry.selectProcessorForKinds(ProcessorCapability.CPU, List.of("sha512")),
			"nor a segment placement decision");
		assertFalse(registry.isConnected("node-1"));

		List<String> frames = capturedFrames(subscriber);
		assertTrue(frames.stream().anyMatch(f -> f.contains("STATE_CHANGED") && f.contains("OFFLINE")),
			"the eviction must announce the OFFLINE transition, not just vanish");
		// Exactly one: the UI treats DISCONNECTED as the departure, and a second one for a worker
		// that already left would resurrect a card that a later REGISTERED had legitimately replaced.
		assertEquals(1, frames.stream().filter(f -> f.contains("DISCONNECTED")).count(),
			"a departure must be announced exactly once");
	}

	@Test
	public void shouldLeaveAWorkerThatIsStillHeartbeatingAlone() {
		ProcessorRegistry registry = new ProcessorRegistry();
		registry.register("node-1", registration("node-1"), openSocket());
		registry.register("node-2", registration("node-2"), openSocket());
		silenceSince(registry, "node-2", Duration.ofMinutes(5));

		assertEquals(1, reaper(registry, null).sweep(Instant.now(), 100));

		assertTrue(registry.isConnected("node-1"), "a worker inside the tolerance must survive");
		assertEquals("node-1", registry.selectProcessor(ProcessorCapability.CPU).nodeId);
	}

	@Test
	public void shouldTolerateExactlyTheConfiguredNumberOfMissedBeats() {
		ProcessorRegistry registry = new ProcessorRegistry();
		registry.register("node-1", registration("node-1"), openSocket());
		// One beat short of the deadline. A worker is evicted for silence, not for lateness:
		// tightening this to a single missed beat evicts a healthy fleet on a GC pause.
		silenceSince(registry, "node-1", Duration.ofMillis(HEARTBEAT_MS * MISSED - 300));

		ProcessorPresenceReaper reaper = reaper(registry, null);
		assertEquals(Duration.ofMillis(HEARTBEAT_MS * MISSED), reaper.getMaxAge());
		assertEquals(0, reaper.sweep(Instant.now(), 100));
		assertTrue(registry.isConnected("node-1"));
	}

	@Test
	public void shouldBoundOneSweep() {
		ProcessorRegistry registry = new ProcessorRegistry();
		for (int i = 0; i < 5; i++) {
			registry.register("node-" + i, registration("node-" + i), openSocket());
			silenceSince(registry, "node-" + i, Duration.ofMinutes(5));
		}

		// A sweep is not allowed to become the outage: a fleet-wide network blip must not
		// evict every worker (and reclaim every task) inside one tick.
		assertEquals(2, reaper(registry, null).sweep(Instant.now(), 2));
		assertEquals(3, registry.getAll().size());
	}

	// ── The reconnect guard ──────────────────────────────────────────────────────────────────────

	@Test
	public void shouldNotEvictAWorkerThatReconnectedUnderTheSameId() {
		ProcessorRegistry registry = new ProcessorRegistry();
		ServerWebSocket oldSocket = openSocket();
		registry.register("node-1", registration("node-1"), oldSocket);
		silenceSince(registry, "node-1", Duration.ofMinutes(5));
		ConnectedProcessor stale = registry.get("node-1");

		// The race the guard exists for: the worker comes back while its old registration is
		// being evicted. presenceChanged() fires from inside the eviction, which is the one
		// place a test can deterministically land the reconnect in the middle of it.
		ServerWebSocket newSocket = openSocket();
		registry.onPresenceChanged(new Runnable() {

			private boolean reconnected;

			@Override
			public void run() {
				if (!reconnected) {
					reconnected = true;
					registry.register("node-1", registration("node-1"), newSocket);
				}
			}
		});

		reaper(registry, null).sweep(Instant.now(), 100);

		ConnectedProcessor live = registry.get("node-1");
		assertNotNull(live, "the reconnection must survive the eviction of the registration it replaced");
		assertNotSame(stale, live, "and it must be the new registration, not the one that was swept");
		assertSame(newSocket, live.ws);
		assertEquals(ProcessorState.ONLINE, live.state);
		assertEquals("node-1", registry.selectProcessor(ProcessorCapability.CPU).nodeId,
			"a worker that came back is placeable again");
	}

	@Test
	public void shouldIgnoreTheCloseOfASupersededSocket() {
		ProcessorRegistry registry = new ProcessorRegistry();
		ServerWebSocket oldSocket = openSocket();
		registry.register("node-1", registration("node-1"), oldSocket);
		ServerWebSocket newSocket = openSocket();
		registry.register("node-1", registration("node-1"), newSocket);

		// Same protection, other half: the old socket's close handler fires after the
		// replacement. Both paths now share one eviction, so both guards have to hold.
		registry.disconnect("node-1", oldSocket);

		assertTrue(registry.isConnected("node-1"));
		assertSame(newSocket, registry.get("node-1").ws);
	}

	// ── Reclaiming the evicted worker's work ─────────────────────────────────────────────────────

	@Test
	public void shouldReclaimTheEvictedWorkersLeasesWithoutWaitingThemOut() {
		CountingDispatcher dispatcher = new CountingDispatcher();
		UUID runUuid = UUID.randomUUID();
		PipelineRunEngine engine = new PipelineRunEngine(retryableGraph(), dispatcher, runUuid);
		PipelineRunRegistry runs = new PipelineRunRegistry();
		runs.register(runUuid, engine);

		engine.start();
		String itemId = engine.onItemDiscovered(MediaRef.of("/media/a.mp4"));
		assertEquals(1, dispatcher.dispatched.size());

		FakeNodeTaskDao dao = new FakeNodeTaskDao();
		// The lease has NOT lapsed - loadExpiredLeases would return nothing. That is the
		// difference this makes: the work moves on the eviction rather than a lease later.
		dao.leased.add(leasedTask(runUuid, UUID.fromString(itemId), "hash", "node-1"));
		dao.leased.add(leasedTask(runUuid, UUID.fromString(itemId), "hash", "node-2"));

		ProcessorRegistry registry = new ProcessorRegistry();
		registry.register("node-1", registration("node-1"), openSocket());
		silenceSince(registry, "node-1", Duration.ofMinutes(5));

		assertEquals(1, reaper(registry, new LeaseReaper(dao, runs)).sweep(Instant.now(), 100));

		assertEquals(2, dispatcher.dispatched.size(), "the evicted worker's task must be re-placed at once");
		assertTrue(dao.expired.isEmpty(), "and not because the lease had lapsed");
	}

	@Test
	public void shouldStillEvictWhenTheReclaimFails() {
		ProcessorRegistry registry = new ProcessorRegistry();
		registry.register("node-1", registration("node-1"), openSocket());
		silenceSince(registry, "node-1", Duration.ofMinutes(5));

		// A dead worker has to leave the fleet even when its work cannot be moved; otherwise a
		// database blip would keep handing new tasks to a machine that is not there. Whatever is
		// missed here still lapses into the LeaseReaper's own sweep.
		LeaseReaper throwing = new LeaseReaper(new ThrowingNodeTaskDao(), new PipelineRunRegistry());
		assertEquals(1, reaper(registry, throwing).sweep(Instant.now(), 100));
		assertFalse(registry.isConnected("node-1"));
	}

	// ── Availability ─────────────────────────────────────────────────────────────────────────────

	@Test
	public void shouldReportTheEvictedWorkersNodesAsUnavailable() {
		NodeDescriptorRegistry descriptors = new NodeDescriptorRegistry();
		descriptors.register(descriptor("sha512"));

		ProcessorRegistry processors = new ProcessorRegistry();
		processors.register("node-1", registration("node-1"), openSocket());

		NodeAvailabilityService availability = new NodeAvailabilityService(descriptors, processors,
			new NodeRegistrationService(descriptors, true));
		assertTrue(availability.availability(false).get("sha512").isAvailable(),
			"a connected worker offers its built-in kinds");

		silenceSince(processors, "node-1", Duration.ofMinutes(5));
		reaper(processors, null).sweep(Instant.now(), 100);

		// The editor greys the node out. Before the sweep existed it stayed enabled and a run
		// using it was accepted, only to be dispatched at a worker that was not there.
		NodeAvailability after = availability.availability(true).get("sha512");
		assertFalse(after.isAvailable(), "an expired worker offers nothing");
		assertEquals(List.of(), after.getProvidedBy());
	}

	// ── Scheduling and the off switch ────────────────────────────────────────────────────────────

	@Test
	public void shouldEvictOnItsOwnTimer() throws Exception {
		ProcessorRegistry registry = new ProcessorRegistry();
		registry.register("node-1", registration("node-1"), openSocket());
		silenceSince(registry, "node-1", Duration.ofMinutes(5));

		// The sweep runs on a background thread nobody watches; a wiring mistake there is
		// invisible to every test that calls sweep() directly.
		ProcessorPresenceReaper reaper = new ProcessorPresenceReaper(registry, null, true, 20, 1);
		reaper.start();
		try {
			assertTrue(awaitGone(registry, "node-1"), "the scheduled sweep must evict without being called");
		} finally {
			reaper.stop();
		}
	}

	@Test
	public void shouldNotStartWhenExpiryIsSwitchedOff() throws Exception {
		ProcessorRegistry registry = new ProcessorRegistry();
		registry.register("node-1", registration("node-1"), openSocket());
		silenceSince(registry, "node-1", Duration.ofMinutes(5));

		// The off switch is for local development: a worker paused on a breakpoint stops
		// heartbeating, and being evicted mid-debug session is the opposite of useful.
		ProcessorPresenceReaper reaper = new ProcessorPresenceReaper(registry, null, false, 20, 1);
		assertFalse(reaper.isEnabled());
		reaper.start();
		try {
			assertFalse(awaitGone(registry, "node-1"), "a disabled reaper must never evict");
		} finally {
			reaper.stop();
		}
	}

	// ── Helpers ──────────────────────────────────────────────────────────────────────────────────

	/** Records dispatches so a reclaim can be observed as a re-dispatch. */
	private static class CountingDispatcher implements NodeDispatcher {

		final List<NodeTask> dispatched = new ArrayList<>();

		@Override
		public String dispatch(NodeTask task) {
			dispatched.add(task);
			return "node-1";
		}
	}

	/** A task store whose lease lookup fails, standing in for a database that is down. */
	private static class ThrowingNodeTaskDao extends FakeNodeTaskDao {

		@Override
		public List<PipelineNodeTask> loadLeasedBy(String processorNodeId, int limit) {
			throw new IllegalStateException("database unavailable");
		}
	}

	private static boolean awaitGone(ProcessorRegistry registry, String nodeId) throws InterruptedException {
		for (int i = 0; i < 50 && registry.isConnected(nodeId); i++) {
			Thread.sleep(20);
		}
		return !registry.isConnected(nodeId);
	}

	private static List<String> capturedFrames(ServerWebSocket subscriber) {
		ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
		verify(subscriber, atLeastOnce()).writeTextMessage(sent.capture());
		return sent.getAllValues();
	}

	private static NodeDescriptor descriptor(String nodeId) {
		return new NodeDescriptor()
			.setNodeId(nodeId)
			.setName(nodeId)
			.setCategory(NodeCategory.ANALYSIS)
			.setInputPorts(List.of(PortSpec.one("media", ContentTypeRegistry.MEDIA_ANY)));
	}

	private static PipelineGraph retryableGraph() {
		JsonObject definition = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "hash").put("type", "sha512")
					.put("options", new JsonObject().put("retryFailed", true))))
			.put("edges", new JsonArray()
				.add(new JsonObject()
					.put("source", "src").put("sourcePort", "media")
					.put("target", "hash").put("targetPort", "media")));
		return new PipelineGraphParser().parse("presence", definition, true, false, 0);
	}

	private static PipelineNodeTask leasedTask(UUID runUuid, UUID itemUuid, String nodeId, String leasedBy) {
		PipelineNodeTask task = new FakePipelineNodeTask();
		task.setUuid(UUID.randomUUID());
		task.setRunUuid(runUuid);
		task.setItemUuid(itemUuid);
		task.setNodeId(nodeId);
		task.setNodeKind("sha512");
		task.setState(NodeTaskState.RUNNING);
		task.setLeasedBy(leasedBy);
		// Deliberately in the future: the lease has not lapsed, so nothing else would move it.
		task.setLeaseExpiresAt(Instant.now().plusSeconds(600));
		return task;
	}

}
