package io.metaloom.loom.nodes.spec;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Registry that holds all known {@link NodeDescriptor} instances.
 * Descriptors are registered at startup and served to the UI via the REST endpoint.
 */
public class NodeDescriptorRegistry {

	private final Map<String, NodeDescriptor> descriptors = new LinkedHashMap<>();

	private final Map<String, NodePortResolver> portResolvers = new LinkedHashMap<>();

	public NodeDescriptorRegistry() {
		for (NodePortResolver resolver : ServiceLoader.load(NodePortResolver.class)) {
			portResolvers.put(resolver.kind(), resolver);
		}
	}

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

	/**
	 * Resolve the effective ports of a node instance.
	 *
	 * <p>
	 * For a fixed kind this is the descriptor's own port lists. For a kind that declares {@link NodeDescriptor#isDynamicPorts()}, the registered
	 * {@link NodePortResolver} derives them from the instance's options — which is what lets a {@code script} node's declared outputs, or one
	 * {@code llm} port per configured prompt, be validated and drawn exactly like a fixed kind's.
	 * </p>
	 *
	 * @param kind
	 *            the node kind
	 * @param options
	 *            the node instance's options; may be null
	 * @return the resolved ports, or {@code null} when the kind is unknown
	 */
	public ResolvedPorts resolvePorts(String kind, Map<String, Object> options) {
		NodeDescriptor descriptor = descriptors.get(kind);
		if (descriptor == null) {
			return null;
		}
		List<PortSpec> inputs = descriptor.getInputPorts();
		List<PortSpec> outputs = descriptor.getOutputPorts();
		NodePortResolver resolver = descriptor.isDynamicPorts() ? portResolvers.get(kind) : null;
		if (resolver != null) {
			Map<String, Object> opts = options == null ? Map.of() : options;
			inputs = resolver.resolveInputPorts(descriptor, opts);
			outputs = resolver.resolveOutputPorts(descriptor, opts);
		}
		return new ResolvedPorts(inputs, outputs, descriptor.getInputGroups(), descriptor.getOutputGroups());
	}

	/**
	 * Register a port resolver programmatically. Normally resolvers are discovered via {@link ServiceLoader}; this exists for tests.
	 */
	public void registerPortResolver(NodePortResolver resolver) {
		portResolvers.put(resolver.kind(), resolver);
	}
}
