package io.metaloom.loom.rest.service.impl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.model.nodes.NodeAvailability;
import io.metaloom.loom.rest.model.nodes.NodeRegistryEventMessage;
import io.metaloom.loom.rest.model.nodes.NodeRegistryEventMessage.Type;

/**
 * Tells open editors when the node registry changed, so a palette loaded ten minutes ago stops lying.
 *
 * <p>
 * Without this the editor never notices a new worker: {@code NodeRegistryContext} fetches the registry
 * once at mount and has no reason to look again. A Python worker connecting after the tab opened would
 * be invisible until someone pressed F5, which is exactly the experience this whole feature exists to
 * remove.
 * </p>
 *
 * <h2>Why availability is diffed here</h2>
 *
 * <p>
 * Presence flips on every connect, disconnect, restart and scale event. Emitting a "go re-fetch" frame
 * for each one would pull ~115 KB through every open tab to communicate a single boolean, so presence
 * changes carry their own delta inline and are only emitted when a node's state actually differs from
 * what was last announced.
 * </p>
 */
@Singleton
public class NodeRegistryEventPublisher {

	private static final Logger log = LoggerFactory.getLogger(NodeRegistryEventPublisher.class);

	private final PipelineEventBroadcaster broadcaster;
	private final NodeAvailabilityService availabilityService;

	/** Last published state per node id, so only genuine changes reach the wire. */
	private final Map<String, Snapshot> published = new LinkedHashMap<>();

	@Inject
	public NodeRegistryEventPublisher(PipelineEventBroadcaster broadcaster, NodeAvailabilityService availabilityService,
		NodeRegistrationService registrations) {
		this.broadcaster = broadcaster;
		this.availabilityService = availabilityService;
		// The service already suppresses a re-announcement of an identical set, so reaching this
		// listener at all means the merged registry really did change.
		registrations.onRegistryChanged(this::publishDescriptorsChanged);
	}

	/**
	 * Listen for worker arrivals and departures.
	 *
	 * <p>
	 * Separate from the constructor because {@link ProcessorRegistry} is what
	 * {@link NodeAvailabilityService} reads, so taking it as a constructor dependency here would close
	 * a cycle. The caller that owns both hands it over instead.
	 * </p>
	 */
	public void attach(ProcessorRegistry processors) {
		if (processors != null) {
			processors.onPresenceChanged(this::publishAvailabilityChanged);
		}
	}

	/** The contract set changed: the client should re-fetch the full list. */
	public void publishDescriptorsChanged() {
		if (broadcaster == null) {
			return;
		}
		log.debug("Node descriptor registry changed; notifying editors");
		broadcaster.broadcastNodeRegistryEvent(new NodeRegistryEventMessage(Type.NODE_DESCRIPTORS_CHANGED));
		// A contract change usually moves availability too (a worker just arrived or left), and the
		// client patches that in place rather than inferring it from the re-fetch.
		publishAvailabilityChanged();
	}

	/**
	 * Presence changed: emit only the entries that actually differ.
	 *
	 * <p>
	 * Called from the worker lifecycle — register, state change, disconnect — where a no-op emit is
	 * the common case, so an unchanged fleet produces no traffic at all.
	 * </p>
	 */
	public void publishAvailabilityChanged() {
		if (broadcaster == null) {
			return;
		}
		Map<String, NodeAvailability> current = availabilityService.availability(false);
		Map<String, NodeAvailability> delta = new LinkedHashMap<>();

		synchronized (published) {
			for (Map.Entry<String, NodeAvailability> entry : current.entrySet()) {
				Snapshot snapshot = Snapshot.of(entry.getValue());
				if (!snapshot.equals(published.get(entry.getKey()))) {
					delta.put(entry.getKey(), entry.getValue());
					published.put(entry.getKey(), snapshot);
				}
			}
			published.keySet().retainAll(current.keySet());
		}
		if (delta.isEmpty()) {
			return;
		}
		broadcaster.broadcastNodeRegistryEvent(new NodeRegistryEventMessage(Type.NODE_AVAILABILITY_CHANGED)
			.setAvailability(delta));
	}

	/**
	 * What counts as a presence change.
	 *
	 * <p>
	 * Deliberately excludes {@code lastSeen}: it moves on every heartbeat of every worker, and treating
	 * that as a change would turn a 10-second heartbeat into a broadcast storm that says nothing. The
	 * editor re-reads it on the next full fetch, which is soon enough for a relative timestamp.
	 * </p>
	 */
	private record Snapshot(boolean available, boolean versionSkew, String source) {

		static Snapshot of(NodeAvailability availability) {
			return new Snapshot(availability.isAvailable(), availability.isVersionSkew(), availability.getSource());
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Snapshot snapshot
				&& available == snapshot.available
				&& versionSkew == snapshot.versionSkew
				&& Objects.equals(source, snapshot.source);
		}
	}
}
