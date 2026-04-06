package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.ProcessorResponse;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.SystemStatusInfo;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.model.processor.workorder.WorkOrder;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.Json;

/**
 * Registry that tracks all connected processor (cortex) nodes.
 * Manages the lifecycle of processor connections including registration,
 * heartbeat tracking, status updates, and work order dispatching.
 */
@Singleton
public class ProcessorRegistry {

	private static final Logger log = LoggerFactory.getLogger(ProcessorRegistry.class);

	private final Map<String, ConnectedProcessor> processors = new ConcurrentHashMap<>();

	@Inject
	public ProcessorRegistry() {
	}

	/**
	 * Register a processor node with its WebSocket connection.
	 */
	public void register(String nodeId, ProcessorRegistration registration, ServerWebSocket ws) {
		ConnectedProcessor processor = new ConnectedProcessor();
		processor.nodeId = nodeId;
		processor.name = registration.getName();
		processor.host = registration.getHost();
		processor.priority = registration.getPriority();
		processor.capabilities = registration.getCapabilities();
		processor.state = ProcessorState.ONLINE;
		processor.lastSeen = Instant.now();
		processor.ws = ws;
		processors.put(nodeId, processor);
		log.info("Processor registered: {} ({})", registration.getName(), nodeId);
	}

	/**
	 * Unregister a processor node.
	 */
	public void unregister(String nodeId) {
		ConnectedProcessor removed = processors.remove(nodeId);
		if (removed != null) {
			log.info("Processor unregistered: {} ({})", removed.name, nodeId);
		}
	}

	/**
	 * Update the last-seen timestamp for a processor (heartbeat).
	 */
	public void heartbeat(String nodeId) {
		ConnectedProcessor processor = processors.get(nodeId);
		if (processor != null) {
			processor.lastSeen = Instant.now();
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
		return processors.values().stream()
			.filter(p -> p.state == ProcessorState.ONLINE)
			.filter(p -> requiredCapability == null || (p.capabilities != null && p.capabilities.contains(requiredCapability)))
			.sorted((a, b) -> Integer.compare(b.priority, a.priority))
			.findFirst()
			.orElse(null);
	}

	/**
	 * Dispatch a work order to a specific processor.
	 */
	public boolean dispatchWorkOrder(String nodeId, WorkOrder workOrder) {
		ConnectedProcessor processor = processors.get(nodeId);
		if (processor == null || processor.ws == null || processor.ws.isClosed()) {
			return false;
		}
		String json = Json.encode(workOrder);
		processor.ws.writeTextMessage(
			"{\"type\":\"WORK_ORDER\",\"body\":" + json + "}");
		return true;
	}

	/**
	 * Build a REST response for a connected processor.
	 */
	public ProcessorResponse toResponse(ConnectedProcessor processor) {
		ProcessorResponse response = new ProcessorResponse();
		response.setUuid(UUID.nameUUIDFromBytes(processor.nodeId.getBytes()));
		response.setName(processor.name);
		response.setHost(processor.host);
		response.setPriority(processor.priority);
		response.setState(processor.state);
		response.setCapabilities(processor.capabilities);
		response.setSystemStatus(processor.systemStatus);
		response.setLastSeen(processor.lastSeen);
		return response;
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
		public ProcessorState state;
		public Instant lastSeen;
		public SystemStatusInfo systemStatus;
		public ServerWebSocket ws;
	}
}
