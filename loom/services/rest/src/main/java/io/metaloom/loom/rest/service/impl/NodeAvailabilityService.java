package io.metaloom.loom.rest.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.NodeDescriptorSource;
import io.metaloom.loom.rest.model.nodes.NodeAvailability;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry.ConnectedProcessor;

/**
 * Answers "can this node actually run right now?" for the pipeline editor.
 *
 * <p>
 * This is the live half of the split the whole design rests on: contracts are durable, presence is
 * live, and they are computed from <strong>different sources</strong> — the contract from
 * {@link NodeDescriptorRegistry}, the presence from {@link ProcessorRegistry}. Nothing here can make a
 * node stop validating; it only decides how the palette sorts and labels it.
 * </p>
 *
 * <h2>Availability is a state query, not a timestamp comparison</h2>
 *
 * <p>
 * A node is available when a worker offering it is in state {@code ONLINE}. It is tempting to derive
 * that from "was this contract seen recently", and it is wrong: a worker announces once, right after
 * registering, and then stays connected for days. That reading would grey out the entire palette one
 * heartbeat interval after a healthy fleet connected.
 * </p>
 */
@Singleton
public class NodeAvailabilityService {

	private final NodeDescriptorRegistry registry;
	private final ProcessorRegistry processors;
	private final NodeRegistrationService registrations;

	@Inject
	public NodeAvailabilityService(NodeDescriptorRegistry registry, ProcessorRegistry processors,
		NodeRegistrationService registrations) {
		this.registry = registry;
		this.processors = processors;
		this.registrations = registrations;
	}

	/**
	 * Fleet state for every known node type.
	 *
	 * @param includeProviders
	 *            whether to name the workers. Worker ids are fleet topology and the descriptor
	 *            endpoints are unauthenticated by design — the editor has to load them before any
	 *            token is in hand — so this is gated on {@code READ_CORTEX_INSTANCE} while
	 *            {@code available} and {@code lastSeen} are not
	 */
	public Map<String, NodeAvailability> availability(boolean includeProviders) {
		Map<String, Set<String>> announcedProviders = registrations.providersByNodeId();
		Map<String, NodeAvailability> result = new LinkedHashMap<>();

		for (NodeDescriptor descriptor : registry.getAll()) {
			String nodeId = descriptor.getNodeId();
			NodeDescriptorSource source = registry.sourceOf(nodeId);

			// A built-in is offered by any online worker whose restrictions admit it, which is the same
			// question dispatch asks. An announced node is offered by the workers that announced it -
			// and only while they are online.
			List<ConnectedProcessor> providers = new ArrayList<>();
			Set<String> announcedBy = announcedProviders.get(nodeId);
			for (ConnectedProcessor processor : processors.getAll()) {
				boolean offers = announcedBy != null && announcedBy.contains(processor.nodeId);
				if (!offers && source == NodeDescriptorSource.BUILTIN) {
					offers = processor.accepts(nodeId);
				}
				if (offers) {
					providers.add(processor);
				}
			}

			NodeAvailability availability = new NodeAvailability()
				.setSource(source == null ? null : source.name())
				.setAvailable(providers.stream().anyMatch(p -> p.state == ProcessorState.ONLINE))
				.setLastSeen(latestHeartbeat(providers))
				.setVersionSkew(registrations.hasVersionSkew(nodeId));

			if (includeProviders) {
				availability.setProvidedBy(providers.stream().map(p -> p.nodeId).sorted().toList());
			}
			result.put(nodeId, availability);
		}
		return result;
	}

	/**
	 * The most recent heartbeat among the workers offering this node.
	 *
	 * <p>
	 * For an available node this reads "seconds ago"; for an unavailable one it is how long ago its
	 * last provider was up, which is the only useful thing the editor can say about a node it cannot
	 * run. Null when no worker has ever offered it.
	 * </p>
	 */
	private Instant latestHeartbeat(List<ConnectedProcessor> providers) {
		Instant latest = null;
		for (ConnectedProcessor processor : providers) {
			if (processor.lastSeen != null && (latest == null || processor.lastSeen.isAfter(latest))) {
				latest = processor.lastSeen;
			}
		}
		return latest;
	}
}
