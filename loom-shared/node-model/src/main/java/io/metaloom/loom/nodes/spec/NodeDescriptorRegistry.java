package io.metaloom.loom.nodes.spec;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry that holds all known {@link NodeDescriptor} instances.
 * Descriptors are registered at startup and served to the UI via the REST endpoint.
 */
public class NodeDescriptorRegistry {

	private final Map<String, NodeDescriptor> descriptors = new LinkedHashMap<>();

	/**
	 * Register a node descriptor. Replaces any existing descriptor with the same kind.
	 *
	 * @param descriptor the descriptor to register
	 * @throws NullPointerException if descriptor or its kind is null
	 */
	public void register(NodeDescriptor descriptor) {
		if (descriptor == null) {
			throw new NullPointerException("descriptor must not be null");
		}
		if (descriptor.getKind() == null) {
			throw new NullPointerException("descriptor kind must not be null");
		}
		descriptors.put(descriptor.getKind(), descriptor);
	}

	/**
	 * Get a descriptor by its kind.
	 *
	 * @param kind the node kind
	 * @return the descriptor or null
	 */
	public NodeDescriptor get(String kind) {
		return descriptors.get(kind);
	}

	/**
	 * Return all registered descriptors.
	 */
	public Collection<NodeDescriptor> getAll() {
		return Collections.unmodifiableCollection(descriptors.values());
	}

	/**
	 * Return the number of registered descriptors.
	 */
	public int size() {
		return descriptors.size();
	}

	/**
	 * Check whether a descriptor for the given kind is registered.
	 */
	public boolean contains(String kind) {
		return descriptors.containsKey(kind);
	}
}
