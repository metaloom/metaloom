package io.metaloom.loom.nodes.spec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry that holds all known {@link NodeDescriptor} instances, in two layers.
 *
 * <p>
 * <strong>BUILTIN</strong> descriptors are compiled into Loom and loaded from
 * {@code ServiceLoader<NodeDescriptorProvider>} at boot. <strong>ANNOUNCED</strong> descriptors arrive
 * from Cortex workers over {@code NODE_REGISTRATION} and are rehydrated from the database at boot, so
 * a custom node stays authorable with no worker connected.
 * </p>
 *
 * <p>
 * A built-in id is never shadowed. Lookups resolve BUILTIN first and only then ANNOUNCED, which is what
 * makes an announced copy of {@code whisper} inert rather than dangerous — and why the ingest path has
 * to <em>report</em> the rejection instead of silently dropping it.
 * </p>
 *
 * <p>
 * Presence of a descriptor says nothing about whether a worker offering it is online. That is deliberate:
 * spec knowledge is durable, worker presence is live, and the two are served side by side rather than
 * merged. A node whose last worker went offline keeps validating and saving; it simply cannot run.
 * </p>
 */
public class NodeDescriptorRegistry {

	/** Written once at boot, read-only afterwards, so iteration order is the ServiceLoader order. */
	private final Map<String, NodeDescriptor> builtin = new LinkedHashMap<>();

	/** Mutated at runtime by ingestion; iterated in node-id order so responses stay deterministic. */
	private final Map<String, NodeDescriptor> announced = new ConcurrentHashMap<>();

	private final Map<String, NodePortResolver> portResolvers = new LinkedHashMap<>();

	public NodeDescriptorRegistry() {
		for (NodePortResolver resolver : ServiceLoader.load(NodePortResolver.class)) {
			portResolvers.put(resolver.kind(), resolver);
		}
	}

	/**
	 * Register a built-in node descriptor. Replaces any existing built-in with the same node id.
	 *
	 * @param descriptor
	 *            the descriptor to register
	 * @throws NullPointerException
	 *             if descriptor or its node id is null
	 */
	public void register(NodeDescriptor descriptor) {
		if (descriptor == null) {
			throw new NullPointerException("descriptor must not be null");
		}
		if (descriptor.getNodeId() == null) {
			throw new NullPointerException("descriptor nodeId must not be null");
		}
		builtin.put(descriptor.getNodeId(), descriptor);
	}

	/**
	 * Add or replace an announced descriptor.
	 *
	 * <p>
	 * A built-in id is refused rather than overwritten, and the refusal is visible to the caller so it
	 * can be reported back to the worker.
	 * </p>
	 *
	 * @return false when the node id belongs to the BUILTIN layer and the announcement was ignored
	 */
	public boolean putAnnounced(NodeDescriptor descriptor) {
		if (descriptor == null || descriptor.getNodeId() == null) {
			throw new NullPointerException("descriptor and its nodeId must not be null");
		}
		if (builtin.containsKey(descriptor.getNodeId())) {
			return false;
		}
		announced.put(descriptor.getNodeId(), descriptor);
		return true;
	}

	/** Drop an announced descriptor. Built-ins are unaffected. */
	public NodeDescriptor removeAnnounced(String nodeId) {
		return nodeId == null ? null : announced.remove(nodeId);
	}

	/**
	 * Replace the whole ANNOUNCED layer — used when rehydrating from the database at boot.
	 *
	 * @param descriptors
	 *            the announced descriptors; built-in ids among them are skipped
	 */
	public void setAnnounced(Collection<NodeDescriptor> descriptors) {
		announced.clear();
		if (descriptors != null) {
			descriptors.forEach(this::putAnnounced);
		}
	}

	/**
	 * Get a descriptor by its node id, BUILTIN taking precedence over ANNOUNCED.
	 *
	 * @param nodeId
	 *            the node type id
	 * @return the descriptor or null
	 */
	public NodeDescriptor get(String nodeId) {
		if (nodeId == null) {
			return null;
		}
		NodeDescriptor descriptor = builtin.get(nodeId);
		return descriptor != null ? descriptor : announced.get(nodeId);
	}

	/**
	 * Which layer {@link #get(String)} would resolve this node id from.
	 *
	 * @return the source, or {@code null} when the id is unknown
	 */
	public NodeDescriptorSource sourceOf(String nodeId) {
		if (nodeId == null) {
			return null;
		}
		if (builtin.containsKey(nodeId)) {
			return NodeDescriptorSource.BUILTIN;
		}
		return announced.containsKey(nodeId) ? NodeDescriptorSource.ANNOUNCED : null;
	}

	/** Whether this node id is a built-in, and therefore cannot be overridden by an announcement. */
	public boolean isBuiltin(String nodeId) {
		return nodeId != null && builtin.containsKey(nodeId);
	}

	/**
	 * Return all descriptors: built-ins in registration order, then announced ones in node-id order.
	 */
	public Collection<NodeDescriptor> getAll() {
		List<NodeDescriptor> all = new ArrayList<>(builtin.values());
		all.addAll(new TreeMap<>(announced).values());
		return Collections.unmodifiableCollection(all);
	}

	/** The built-in layer only — the descriptors compiled into this Loom build. */
	public Collection<NodeDescriptor> getBuiltin() {
		return Collections.unmodifiableCollection(builtin.values());
	}

	/** The announced layer only, in node-id order. */
	public Collection<NodeDescriptor> getAnnounced() {
		return Collections.unmodifiableCollection(new TreeMap<>(announced).values());
	}

	/** The node ids of the announced layer. */
	public Set<String> announcedNodeIds() {
		return Set.copyOf(announced.keySet());
	}

	/**
	 * Return the number of registered descriptors across both layers.
	 */
	public int size() {
		return getAll().size();
	}

	/**
	 * Check whether a descriptor for the given node id is registered in either layer.
	 */
	public boolean contains(String nodeId) {
		return nodeId != null && (builtin.containsKey(nodeId) || announced.containsKey(nodeId));
	}

	/**
	 * Resolve the effective ports of a node instance.
	 *
	 * <p>
	 * For a fixed node type this is the descriptor's own port lists. For a type that declares
	 * {@link NodeDescriptor#isDynamicPorts()}, the registered {@link NodePortResolver} derives them from
	 * the instance's options — which is what lets a {@code script} node's declared outputs, or one
	 * {@code llm} port per configured prompt, be validated and drawn exactly like a fixed type's.
	 * </p>
	 *
	 * <p>
	 * An <em>announced</em> type that declares dynamic ports has no resolver on this side of the wire —
	 * the resolver class only exists on the worker — so it falls back to its declared static port lists.
	 * It stays authorable; it just does not gain per-option ports.
	 * </p>
	 *
	 * @param nodeId
	 *            the node type id
	 * @param options
	 *            the node instance's options; may be null
	 * @return the resolved ports, or {@code null} when the type is unknown
	 */
	public ResolvedPorts resolvePorts(String nodeId, Map<String, Object> options) {
		NodeDescriptor descriptor = get(nodeId);
		if (descriptor == null) {
			return null;
		}
		List<PortSpec> inputs = descriptor.getInputPorts();
		List<PortSpec> outputs = descriptor.getOutputPorts();
		NodePortResolver resolver = descriptor.isDynamicPorts() ? portResolvers.get(nodeId) : null;
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

	/**
	 * The content-type vocabulary as the editor should see it: the compiled-in types, plus one
	 * synthesized entry for every {@code family/subtype} an announced descriptor mentions that Loom has
	 * never heard of.
	 *
	 * <p>
	 * There is no {@code contentTypes} block on the wire, and there does not need to be:
	 * {@link ContentTypeLattice#isAssignable} parses ids structurally and never consults a registry, so
	 * {@code struct/nsfw} validates and connects the moment it is announced. All that is missing is a
	 * label for the editor's tooltip, and that can be derived. The cost is a machine-made label
	 * ("Nsfw"); the benefit is that a third party adds a content type by using it.
	 * </p>
	 */
	public List<ContentType> contentTypes() {
		Map<String, ContentType> merged = new LinkedHashMap<>();
		for (ContentType type : ContentTypeRegistry.all()) {
			merged.put(type.getId(), type);
		}
		List<String> discovered = new ArrayList<>();
		for (NodeDescriptor descriptor : announced.values()) {
			collectContentTypes(descriptor.getInputPorts(), merged, discovered);
			collectContentTypes(descriptor.getOutputPorts(), merged, discovered);
		}
		Collections.sort(discovered);
		for (String id : discovered) {
			merged.put(id, synthesizeContentType(id));
		}
		return List.copyOf(merged.values());
	}

	private static void collectContentTypes(List<PortSpec> ports, Map<String, ContentType> known, List<String> discovered) {
		if (ports == null) {
			return;
		}
		for (PortSpec port : ports) {
			String id = port.getContentType();
			if (id != null && !known.containsKey(id) && !discovered.contains(id)) {
				discovered.add(id);
			}
		}
	}

	/**
	 * Build a {@link ContentType} for an id nobody declared: family from the id, label title-cased from
	 * the subtype.
	 */
	static ContentType synthesizeContentType(String id) {
		String subtype = ContentTypeLattice.subtype(id);
		String label = titleCase(subtype == null ? id : subtype);
		return new ContentType(id, label, "Announced by a Cortex worker");
	}

	private static String titleCase(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		if ("*".equals(value)) {
			return "Any";
		}
		StringBuilder sb = new StringBuilder(value.length());
		boolean upper = true;
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c == '-' || c == '_') {
				sb.append(' ');
				upper = true;
				continue;
			}
			sb.append(upper ? Character.toUpperCase(c) : c);
			upper = false;
		}
		return sb.toString();
	}
}
