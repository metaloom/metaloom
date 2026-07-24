package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.cortex.CortexInstance;
import io.metaloom.loom.db.model.cortex.CortexInstanceDao;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.ProcessorResponse;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.SystemStatusInfo;
import io.metaloom.loom.rest.model.processor.event.ProcessorEventMessage;
import io.metaloom.loom.rest.model.processor.event.ProcessorEventType;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessage;
import io.metaloom.loom.rest.model.processor.message.ProcessorMessageType;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

/**
 * Registry that tracks all connected processor (cortex) nodes.
 * Manages the lifecycle of processor connections including registration,
 * heartbeat tracking, status updates, and work order dispatching.
 */
@Singleton
public class ProcessorRegistry {

	private static final Logger log = LoggerFactory.getLogger(ProcessorRegistry.class);

	private final Map<String, ConnectedProcessor> processors = new ConcurrentHashMap<>();

	/**
	 * Durable store of registered instances and their admin-managed restrictions. May be
	 * null in pure-unit selection tests that exercise the in-memory registry without a DB.
	 */
	private final DaoCollection daos;

	/**
	 * Fan-out for live processor events to UI clients over the single UI events socket.
	 * May be null in pure-unit tests that exercise the in-memory registry without the
	 * broadcaster; every emit is guarded so those tests stay broadcast-free.
	 */
	private final PipelineEventBroadcaster broadcaster;

	@Inject
	public ProcessorRegistry(DaoCollection daos, PipelineEventBroadcaster broadcaster) {
		this.daos = daos;
		this.broadcaster = broadcaster;
	}

	/**
	 * Construct a registry with persistence but without event broadcasting. Used by
	 * persistence tests; {@link #register} reconciles the durable record but emits no
	 * UI events.
	 */
	public ProcessorRegistry(DaoCollection daos) {
		this(daos, null);
	}

	/**
	 * Construct a registry without persistence or event broadcasting. Used by tests that
	 * only exercise the in-memory selection logic; {@link #register} then behaves exactly
	 * as before and emits no events.
	 */
	public ProcessorRegistry() {
		this(null, null);
	}

	/**
	 * Register a processor node with its WebSocket connection.
	 *
	 * <p>The worker-announced whitelist/blacklist are applied as the DEFAULT. When a
	 * {@code cortex_instance} record already carries an administrator-managed restriction,
	 * that OVERRIDE is applied to the in-memory {@link ConnectedProcessor} instead of
	 * blindly trusting what the worker announced, and it survives reconnects.</p>
	 */
	public void register(String nodeId, ProcessorRegistration registration, ServerWebSocket ws) {
		ConnectedProcessor processor = new ConnectedProcessor();
		processor.nodeId = nodeId;
		processor.name = registration.getName();
		processor.host = registration.getHost();
		processor.priority = registration.getPriority();
		processor.capabilities = registration.getCapabilities();
		processor.nodeWhitelist = registration.getNodeWhitelist();
		processor.nodeBlacklist = registration.getNodeBlacklist();
		processor.state = ProcessorState.ONLINE;
		processor.lastSeen = Instant.now();
		processor.ws = ws;

		// Reconcile against the durable record: persist identity + presence, and let an
		// admin-managed restriction override the announced one.
		reconcilePersistedRestriction(nodeId, registration, processor);

		processors.put(nodeId, processor);
		log.info("Processor registered: {} ({})", registration.getName(), nodeId);

		broadcast(new ProcessorEventMessage(ProcessorEventType.REGISTERED, nodeId)
			.setProcessor(toResponse(processor)));
	}

	/**
	 * Persist the worker's identity/presence into {@code cortex_instance} and apply any
	 * administrator-managed whitelist/blacklist override to the connected processor.
	 *
	 * <p>On the first registration the announced restriction is seeded as the record's
	 * default; on later registrations the persisted restriction is authoritative (the
	 * admin may have edited it), so it wins over whatever the worker re-announces.</p>
	 */
	private void reconcilePersistedRestriction(String nodeId, ProcessorRegistration registration, ConnectedProcessor processor) {
		if (daos == null) {
			return;
		}
		try {
			CortexInstanceDao dao = daos.cortexInstanceDao();
			CortexInstance instance = dao.loadByNodeId(nodeId);
			if (instance == null) {
				// First registration: seed the record from what the worker announced.
				instance = dao.createCortexInstance(nodeId, registration.getName());
				instance.setNodeWhitelist(registration.getNodeWhitelist());
				instance.setNodeBlacklist(registration.getNodeBlacklist());
			} else {
				// Known worker: keep the (possibly admin-edited) restriction, refresh identity.
				instance.setName(registration.getName());
			}
			instance.setHost(registration.getHost());
			instance.setPriority(registration.getPriority());
			instance.setState(processor.state == null ? null : processor.state.name());
			instance.setLastSeen(processor.lastSeen);
			CortexInstance persisted = dao.upsertByNodeId(instance);

			// The persisted record is the override: apply its restriction to the live processor.
			processor.nodeWhitelist = persisted.getNodeWhitelist();
			processor.nodeBlacklist = persisted.getNodeBlacklist();
		} catch (Exception e) {
			// Persistence must never take a worker offline: fall back to the announced
			// restriction (already set on the processor) and keep serving.
			log.warn("Could not persist/reconcile cortex instance '{}'; using announced restriction", nodeId, e);
		}
	}

	/**
	 * Unregister a processor node.
	 */
	public void unregister(String nodeId) {
		ConnectedProcessor removed = processors.remove(nodeId);
		if (removed != null) {
			log.info("Processor unregistered: {} ({})", removed.name, nodeId);
			broadcast(new ProcessorEventMessage(ProcessorEventType.DISCONNECTED, nodeId));
		}
	}

	/**
	 * Update the last-seen timestamp for a processor (heartbeat).
	 */
	public void heartbeat(String nodeId) {
		ConnectedProcessor processor = processors.get(nodeId);
		if (processor != null) {
			processor.lastSeen = Instant.now();
			broadcast(new ProcessorEventMessage(ProcessorEventType.HEARTBEAT, nodeId)
				.setLastSeen(processor.lastSeen));
		}
	}

	/**
	 * Update the system status for a processor.
	 */
	public void updateStatus(String nodeId, SystemStatusInfo status) {
		ConnectedProcessor processor = processors.get(nodeId);
		if (processor != null) {
			processor.systemStatus = status;
			processor.lastSeen = Instant.now();
			broadcast(new ProcessorEventMessage(ProcessorEventType.STATUS_UPDATED, nodeId)
				.setProcessor(toResponse(processor)));
		}
	}

	/**
	 * Update the state of a processor.
	 */
	public void updateState(String nodeId, ProcessorState state) {
		ConnectedProcessor processor = processors.get(nodeId);
		if (processor != null) {
			processor.state = state;
			processor.lastSeen = Instant.now();
			broadcast(new ProcessorEventMessage(ProcessorEventType.STATE_CHANGED, nodeId)
				.setProcessor(toResponse(processor)));
		}
	}

	/**
	 * Fan a processor event out to UI subscribers when a broadcaster is wired.
	 * No-op in unit tests that construct the registry without one.
	 */
	private void broadcast(ProcessorEventMessage event) {
		if (broadcaster != null) {
			broadcaster.broadcastProcessorEvent(event);
		}
	}

	/**
	 * Get a connected processor by its node ID.
	 */
	public ConnectedProcessor get(String nodeId) {
		return processors.get(nodeId);
	}

	/**
	 * Return all currently tracked processors.
	 */
	public Collection<ConnectedProcessor> getAll() {
		return processors.values();
	}

	/**
	 * Find a suitable processor for the given required capability.
	 * Selects the online processor with the highest priority that has the required capability.
	 *
	 * @return the best matching processor, or null if none is available
	 */
	public ConnectedProcessor selectProcessor(ProcessorCapability requiredCapability) {
		return selectProcessor(requiredCapability, null);
	}

	/**
	 * Find a worker for a specific kind of node.
	 *
	 * <p>Adds whitelist filtering to the capability check, so a run can be spread over
	 * a pool of workers that are each restricted to part of the graph - GPU boxes for
	 * embeddings, the host holding the media mount for filesystem sources.</p>
	 *
	 * <p>Selection is still by declared priority. Load-based selection is deliberately
	 * not attempted: {@code cpuLoad} is known to be broken, and scheduling on a wrong
	 * metric is worse than scheduling on none.</p>
	 *
	 * @param requiredCapability capability the worker must have, or null for any
	 * @param nodeKind           kind of node to run, or null to ignore whitelists
	 * @return the best matching processor, or null when none will take it
	 */
	public ConnectedProcessor selectProcessor(ProcessorCapability requiredCapability, String nodeKind) {
		return processors.values().stream()
			.filter(p -> p.state == ProcessorState.ONLINE)
			.filter(p -> requiredCapability == null || (p.capabilities != null && p.capabilities.contains(requiredCapability)))
			.filter(p -> nodeKind == null || p.accepts(nodeKind))
			.sorted((a, b) -> Integer.compare(b.priority, a.priority))
			.findFirst()
			.orElse(null);
	}

	/**
	 * Find a worker permitted to run every one of the given kinds.
	 *
	 * <p>Distinct from calling {@link #selectProcessor(ProcessorCapability, String)}
	 * per kind: a segment must run entirely on one machine, so partial coverage across
	 * the fleet is no coverage at all.</p>
	 *
	 * @param requiredCapability capability the worker must have, or null for any
	 * @param nodeKinds          every kind the work needs
	 * @return the best matching processor, or null when none covers them all
	 */
	public ConnectedProcessor selectProcessorForKinds(ProcessorCapability requiredCapability,
		java.util.List<String> nodeKinds) {
		return processors.values().stream()
			.filter(p -> p.state == ProcessorState.ONLINE)
			.filter(p -> requiredCapability == null || (p.capabilities != null && p.capabilities.contains(requiredCapability)))
			.filter(p -> nodeKinds == null || nodeKinds.stream().allMatch(p::accepts))
			.sorted((a, b) -> Integer.compare(b.priority, a.priority))
			.findFirst()
			.orElse(null);
	}

	/**
	 * Send a typed message to a specific processor.
	 *
	 * <p>The envelope used to be assembled by string concatenation, which happened to
	 * survive small work orders and would not survive a payload containing a quote, a
	 * newline, or a null body. It is now serialised like any other model.</p>
	 *
	 * @param nodeId the target processor
	 * @param type   the message type
	 * @param body   the payload, may be null for types that carry none
	 * @return true when the message was written; false when the processor is unknown
	 *         or its socket is gone
	 */
	public boolean send(String nodeId, ProcessorMessageType type, Object body) {
		ConnectedProcessor processor = processors.get(nodeId);
		if (processor == null || processor.ws == null || processor.ws.isClosed()) {
			log.debug("Cannot send {} to '{}': processor unknown or disconnected", type, nodeId);
			return false;
		}
		ProcessorMessage message = new ProcessorMessage(type,
			body == null ? null : JsonObject.mapFrom(body));
		processor.ws.writeTextMessage(Json.encode(message));
		return true;
	}

	/**
	 * Build a REST response for a connected processor.
	 */
	public ProcessorResponse toResponse(ConnectedProcessor processor) {
		ProcessorResponse response = new ProcessorResponse();
		response.setUuid(UUID.nameUUIDFromBytes(processor.nodeId.getBytes()));
		response.setNodeId(processor.nodeId);
		response.setName(processor.name);
		response.setHost(processor.host);
		response.setPriority(processor.priority);
		response.setState(processor.state);
		response.setCapabilities(processor.capabilities);
		response.setSystemStatus(processor.systemStatus);
		response.setLastSeen(processor.lastSeen);
		response.setNodeWhitelist(processor.nodeWhitelist);
		response.setNodeBlacklist(processor.nodeBlacklist);
		// A live worker is persisted whenever a durable store is wired (register() upserts it).
		response.setPersisted(daos != null);
		return response;
	}

	/**
	 * Build a REST response for a persisted-but-offline cortex instance, i.e. a worker Loom
	 * remembers (with its admin-managed restrictions) but which is not currently connected.
	 */
	public ProcessorResponse toResponse(CortexInstance instance) {
		ProcessorResponse response = new ProcessorResponse();
		response.setUuid(UUID.nameUUIDFromBytes(instance.getNodeId().getBytes()));
		response.setNodeId(instance.getNodeId());
		response.setName(instance.getName());
		response.setHost(instance.getHost());
		response.setPriority(instance.getPriority());
		response.setState(ProcessorState.OFFLINE);
		response.setCapabilities(null);
		response.setSystemStatus(null);
		response.setLastSeen(instance.getLastSeen());
		response.setNodeWhitelist(instance.getNodeWhitelist());
		response.setNodeBlacklist(instance.getNodeBlacklist());
		response.setPersisted(true);
		return response;
	}

	/**
	 * Build the list shown in the Cortex view: every live processor plus every persisted
	 * cortex instance that is not currently connected, so an operator can still see and edit
	 * an offline worker's restrictions. Live rows win over persisted ones for the same nodeId.
	 *
	 * <p>The durable lookup is defensive: a persistence failure degrades to the live-only list
	 * rather than breaking the view.</p>
	 */
	public List<ProcessorResponse> listAllResponses() {
		List<ProcessorResponse> responses = new ArrayList<>();
		Set<String> liveNodeIds = new HashSet<>();
		for (ConnectedProcessor p : processors.values()) {
			liveNodeIds.add(p.nodeId);
			responses.add(toResponse(p));
		}
		if (daos != null) {
			try {
				daos.cortexInstanceDao().findAll().forEach(instance -> {
					if (!liveNodeIds.contains(instance.getNodeId())) {
						responses.add(toResponse(instance));
					}
				});
			} catch (Exception e) {
				log.warn("Could not enumerate persisted cortex instances; returning live processors only", e);
			}
		}
		return responses;
	}

	/**
	 * @return true when a worker with the given node id is currently connected.
	 */
	public boolean isConnected(String nodeId) {
		return processors.containsKey(nodeId);
	}

	/**
	 * Load a persisted (possibly offline) cortex instance as a response.
	 *
	 * @return the response, or null when no durable record exists (or persistence is disabled)
	 */
	public ProcessorResponse loadPersisted(String nodeId) {
		if (daos == null) {
			return null;
		}
		CortexInstance instance = daos.cortexInstanceDao().loadByNodeId(nodeId);
		return instance == null ? null : toResponse(instance);
	}

	/**
	 * Persist administrator-managed node-kind restrictions for a worker and, when that worker
	 * is currently connected, re-apply them to the live {@link ConnectedProcessor} so
	 * {@link #selectProcessorForKinds} honours the change immediately.
	 *
	 * @param nodeId      stable worker identity
	 * @param whitelist   kinds the worker may run (empty/null = unrestricted)
	 * @param blacklist   kinds the worker must refuse (wins over the whitelist)
	 * @param editorUuid  the acting user, recorded on the durable row
	 * @return the updated response (live processor when connected, else the persisted row)
	 */
	public ProcessorResponse updateRestrictions(String nodeId, Set<String> whitelist, Set<String> blacklist, UUID editorUuid) {
		if (daos == null) {
			throw new IllegalStateException("Cannot persist restrictions without a database");
		}
		CortexInstanceDao dao = daos.cortexInstanceDao();
		CortexInstance instance = dao.loadByNodeId(nodeId);
		if (instance == null) {
			ConnectedProcessor live = processors.get(nodeId);
			instance = dao.createCortexInstance(nodeId, live != null ? live.name : nodeId);
		}
		instance.setNodeWhitelist(whitelist);
		instance.setNodeBlacklist(blacklist);
		if (editorUuid != null) {
			instance.setEditorUuid(editorUuid);
		}
		CortexInstance persisted = dao.upsertByNodeId(instance);

		ConnectedProcessor live = processors.get(nodeId);
		if (live != null) {
			live.nodeWhitelist = persisted.getNodeWhitelist();
			live.nodeBlacklist = persisted.getNodeBlacklist();
			ProcessorResponse response = toResponse(live);
			// Let open Cortex views reflect the new restriction without a reload.
			broadcast(new ProcessorEventMessage(ProcessorEventType.STATUS_UPDATED, nodeId).setProcessor(response));
			return response;
		}
		return toResponse(persisted);
	}

	/**
	 * Forget a persisted cortex instance. Only valid for a worker that is not currently
	 * connected; the caller is responsible for that guard ({@link #isConnected(String)}).
	 *
	 * @return true when a durable record was removed, false when none existed
	 */
	public boolean forget(String nodeId) {
		if (daos == null) {
			return false;
		}
		CortexInstanceDao dao = daos.cortexInstanceDao();
		CortexInstance instance = dao.loadByNodeId(nodeId);
		if (instance == null) {
			return false;
		}
		// The cortex_instance_node_kind child rows cascade on delete (see V2.33 migration).
		dao.delete(instance.getUuid());
		return true;
	}

	/**
	 * Represents a processor node that is currently connected via WebSocket.
	 */
	public static class ConnectedProcessor {
		public String nodeId;
		public String name;
		public String host;
		public int priority;
		public Set<ProcessorCapability> capabilities;
		/** Node kinds this worker accepts; null or empty means "anything". */
		public Set<String> nodeWhitelist;
		/** Node kinds this worker refuses; null or empty means "refuse nothing". */
		public Set<String> nodeBlacklist;

		/**
		 * @param nodeKind the kind of work
		 * @return true when this worker will take it
		 */
		public boolean accepts(String nodeKind) {
			// The blacklist wins: a kind explicitly refused is never accepted, even when
			// the whitelist would admit it.
			if (nodeBlacklist != null && nodeBlacklist.contains(nodeKind)) {
				return false;
			}
			// An empty whitelist means unrestricted, so a worker registered before
			// whitelisting existed keeps receiving everything rather than silently
			// dropping out of the pool.
			return nodeWhitelist == null || nodeWhitelist.isEmpty() || nodeWhitelist.contains(nodeKind);
		}
		public ProcessorState state;
		public Instant lastSeen;
		public SystemStatusInfo systemStatus;
		public ServerWebSocket ws;
	}
}
