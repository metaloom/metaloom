package io.metaloom.loom.pipeline.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.metaloom.loom.nodes.spec.Cardinality;
import io.metaloom.loom.nodes.spec.ContentTypeLattice;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.PortGroup;
import io.metaloom.loom.nodes.spec.PortGroupMode;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.nodes.spec.ResolvedPorts;

/**
 * Checks a wired graph against the nodes' declared ports and works out how often each node runs.
 *
 * <p>
 * Everything here is <strong>static</strong> — it happens when a definition is saved and again when
 * a run starts, never at dispatch time. That is deliberate: a pipeline that cannot work should be
 * refused while its author is looking at it, not halfway through a run over ten thousand assets.
 * </p>
 *
 * <h2>Effective multiplicity</h2>
 *
 * <p>
 * A port's <em>effective</em> cardinality is not simply what it declares. A node that runs once per
 * element produces one output element per run, so even its {@code ONE} outputs become a sequence
 * when seen from further downstream:
 * </p>
 *
 * <pre>
 * eff(output port p of node n) = MANY  if p declares MANY
 *                              = MANY  if n runs PER_ELEMENT
 *                              = ONE   otherwise
 *
 * mode(n) = PER_ELEMENT  iff some ONE-cardinality input of n is bound to an effectively-MANY output
 *         = SINGLE       otherwise   // MANY inputs consuming MANY is the gather, and runs once
 * </pre>
 *
 * <p>
 * The propagation runs in topological order, so a node's inputs are always resolved before it is
 * classified.
 * </p>
 */
public class PortGraphAnalyzer {

	private final NodeDescriptorRegistry registry;

	public PortGraphAnalyzer(NodeDescriptorRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Validate the port wiring and stamp each node's execution mode.
	 *
	 * @param graphName
	 *            used only in error messages
	 * @param nodes
	 *            every node, keyed by id
	 * @param order
	 *            a topological ordering of {@code nodes}
	 * @throws GraphValidationException
	 *             when the wiring cannot run as drawn
	 */
	public void analyze(String graphName, Map<String, PipelineGraphNode> nodes, List<String> order) {
		if (registry == null) {
			// No descriptors available (some tests, and the in-memory backend). Port checking is
			// meaningless without them; leave every node SINGLE rather than inventing verdicts.
			return;
		}

		Map<String, ResolvedPorts> ports = new LinkedHashMap<>();
		for (PipelineGraphNode node : nodes.values()) {
			ResolvedPorts resolved = registry.resolvePorts(node.getKind(), node.getOptions());
			if (resolved == null) {
				throw new GraphValidationException("Pipeline '" + graphName + "' node '" + node.getId()
					+ "' has unknown type '" + node.getKind() + "'");
			}
			ports.put(node.getId(), resolved);
		}

		for (PipelineGraphNode node : nodes.values()) {
			validateBindings(graphName, node, nodes, ports);
			validateSatisfaction(graphName, node, ports.get(node.getId()));
		}
		validateExclusiveOutputs(graphName, nodes, ports);

		stampBindings(nodes, order, ports);

		computeExecutionModes(graphName, nodes, order, ports);
	}

	/**
	 * Stamp each binding with everything the engine needs to act on it without re-resolving
	 * descriptors on every dispatch: its target port's cardinality, and whether it carries branch
	 * routing.
	 *
	 * <p>
	 * This walks {@code order} rather than the node map because {@code routed} is <strong>inherited
	 * </strong>: a node is on a routed path if one of its own bindings is routed, so a producer has
	 * to be classified before any of its consumers. See {@link InputBinding#routed()} for why a
	 * one-hop rule is not enough.
	 * </p>
	 *
	 * <p>
	 * Note that {@code analyze} returns early when there is no descriptor registry, so a graph parsed
	 * without one stamps nothing and behaves exactly as it did before port routing existed.
	 * </p>
	 */
	private void stampBindings(Map<String, PipelineGraphNode> nodes, List<String> order, Map<String, ResolvedPorts> ports) {
		Set<String> routedNodes = new LinkedHashSet<>();

		for (String nodeId : order) {
			PipelineGraphNode node = nodes.get(nodeId);
			if (node == null) {
				continue;
			}
			ResolvedPorts own = ports.get(nodeId);
			List<InputBinding> enriched = new ArrayList<>();
			boolean onRoutedPath = false;

			for (InputBinding binding : node.getInputBindings()) {
				PortSpec target = own.input(binding.targetPortId());
				ResolvedPorts producer = ports.get(binding.sourceNodeId());
				PortSpec source = producer == null ? null : producer.output(binding.sourcePortId());

				boolean selective = source != null && source.isSelective();
				boolean routed = selective || routedNodes.contains(binding.sourceNodeId());
				onRoutedPath |= routed;

				enriched.add(binding
					.withTargetCardinality(target != null && target.isMany())
					.withRouting(selective, routed));
			}

			node.setInputBindings(enriched);
			if (onRoutedPath) {
				routedNodes.add(nodeId);
			}
		}
	}

	/**
	 * Rules 1, 2 and 4: the ports exist, the types are assignable, and only a {@code MANY} input
	 * accepts more than one edge.
	 */
	private void validateBindings(String graphName, PipelineGraphNode node,
		Map<String, PipelineGraphNode> nodes, Map<String, ResolvedPorts> ports) {

		ResolvedPorts own = ports.get(node.getId());
		Map<String, Integer> edgesPerPort = new LinkedHashMap<>();

		for (InputBinding binding : node.getInputBindings()) {
			PortSpec target = own.input(binding.targetPortId());
			if (target == null) {
				throw new GraphValidationException("Pipeline '" + graphName + "' node '" + node.getId()
					+ "' has no input port '" + binding.targetPortId() + "'");
			}
			PipelineGraphNode sourceNode = nodes.get(binding.sourceNodeId());
			if (sourceNode == null) {
				throw new GraphValidationException("Pipeline '" + graphName + "' node '" + node.getId()
					+ "' input '" + binding.targetPortId() + "' references unknown node '"
					+ binding.sourceNodeId() + "'");
			}
			PortSpec source = ports.get(binding.sourceNodeId()).output(binding.sourcePortId());
			if (source == null) {
				throw new GraphValidationException("Pipeline '" + graphName + "' node '"
					+ binding.sourceNodeId() + "' has no output port '" + binding.sourcePortId()
					+ "' (wired to '" + node.getId() + "." + binding.targetPortId() + "')");
			}
			if (!ContentTypeLattice.isAssignable(source.getContentType(), target.getContentType())) {
				throw new GraphValidationException("Pipeline '" + graphName + "' cannot connect "
					+ binding.sourceNodeId() + "." + binding.sourcePortId() + " (" + source.getContentType()
					+ ") to " + node.getId() + "." + binding.targetPortId() + " (" + target.getContentType()
					+ "): incompatible content types");
			}
			int count = edgesPerPort.merge(binding.targetPortId(), 1, Integer::sum);
			if (count > 1 && !target.isMany()) {
				throw new GraphValidationException("Pipeline '" + graphName + "' node '" + node.getId()
					+ "' input port '" + binding.targetPortId() + "' takes one element but has "
					+ count + " incoming edges; declare it as a sequence to accept several");
			}
		}
	}

	/**
	 * Rule 3: required ports are wired, and a group gets exactly as many members as it allows.
	 */
	private void validateSatisfaction(String graphName, PipelineGraphNode node, ResolvedPorts own) {
		if (node.isSource()) {
			return;
		}
		Set<String> wired = new LinkedHashSet<>();
		for (InputBinding binding : node.getInputBindings()) {
			wired.add(binding.targetPortId());
		}

		for (PortSpec port : own.inputs()) {
			if (port.getGroup() != null) {
				continue; // the group owns satisfaction for its members
			}
			if (port.isRequired() && !wired.contains(port.getId())) {
				throw new GraphValidationException("Pipeline '" + graphName + "' node '" + node.getId()
					+ "' requires input '" + port.getId() + "' (" + port.getContentType() + ") but nothing is connected");
			}
		}

		for (PortGroup group : own.inputGroups()) {
			if (group.getMode() != PortGroupMode.XOR) {
				continue;
			}
			List<PortSpec> members = own.inputsInGroup(group.getId());
			long wiredMembers = members.stream().filter(p -> wired.contains(p.getId())).count();
			String names = members.stream().map(PortSpec::getId).reduce((a, b) -> a + ", " + b).orElse("");
			if (wiredMembers > 1) {
				throw new GraphValidationException("Pipeline '" + graphName + "' node '" + node.getId()
					+ "' accepts only one of [" + names + "] but " + wiredMembers + " are connected");
			}
			if (wiredMembers == 0 && group.isRequired()) {
				throw new GraphValidationException("Pipeline '" + graphName + "' node '" + node.getId()
					+ "' requires one of [" + names + "] to be connected");
			}
		}
	}

	/**
	 * Rule 3, output side: at most one member of an {@code EXCLUSIVE} group may be wired, because
	 * selecting one is what renders its siblings inoperable.
	 */
	private void validateExclusiveOutputs(String graphName, Map<String, PipelineGraphNode> nodes,
		Map<String, ResolvedPorts> ports) {

		Map<String, Set<String>> demanded = demandedByNode(nodes);
		for (PipelineGraphNode node : nodes.values()) {
			ResolvedPorts own = ports.get(node.getId());
			Set<String> used = demanded.getOrDefault(node.getId(), Set.of());
			for (PortGroup group : own.outputGroups()) {
				if (group.getMode() != PortGroupMode.EXCLUSIVE) {
					continue;
				}
				List<PortSpec> members = own.outputsInGroup(group.getId());
				long usedMembers = members.stream().filter(p -> used.contains(p.getId())).count();
				if (usedMembers > 1) {
					String names = members.stream().map(PortSpec::getId).reduce((a, b) -> a + ", " + b).orElse("");
					throw new GraphValidationException("Pipeline '" + graphName + "' node '" + node.getId()
						+ "' can drive only one of [" + names + "] but " + usedMembers + " are connected");
				}
			}
		}
	}

	/**
	 * Which output ports have at least one outgoing edge, per node.
	 */
	public static Map<String, Set<String>> demandedByNode(Map<String, PipelineGraphNode> nodes) {
		Map<String, Set<String>> demanded = new LinkedHashMap<>();
		for (PipelineGraphNode node : nodes.values()) {
			for (InputBinding binding : node.getInputBindings()) {
				demanded.computeIfAbsent(binding.sourceNodeId(), k -> new LinkedHashSet<>())
					.add(binding.sourcePortId());
			}
		}
		return demanded;
	}

	/**
	 * Rule 5: propagate effective multiplicity and classify every node, rejecting the two shapes v1
	 * deliberately does not support.
	 */
	private void computeExecutionModes(String graphName, Map<String, PipelineGraphNode> nodes,
		List<String> order, Map<String, ResolvedPorts> ports) {

		// (nodeId, portId) -> is this port effectively a sequence?
		Map<String, Boolean> effectiveMany = new LinkedHashMap<>();

		for (String nodeId : order) {
			PipelineGraphNode node = nodes.get(nodeId);
			if (node == null) {
				continue;
			}
			ResolvedPorts own = ports.get(nodeId);

			// Which fan-out, if any, drives this node?
			String driver = null;
			String driverVia = null;
			for (InputBinding binding : node.getInputBindings()) {
				PortSpec target = own.input(binding.targetPortId());
				if (target == null || target.getCardinality() == Cardinality.MANY) {
					// A MANY input gathers the sequence instead of iterating it. This single branch
					// is the whole implicit join: no node type, no configuration, just a node whose
					// input happens to accept more than one element.
					continue;
				}
				boolean many = Boolean.TRUE.equals(
					effectiveMany.get(key(binding.sourceNodeId(), binding.sourcePortId())));
				if (!many) {
					continue;
				}
				String candidate = driverOf(nodes, binding.sourceNodeId());
				if (driver == null) {
					driver = candidate;
					driverVia = binding.targetPortId();
				} else if (!driver.equals(candidate)) {
					throw new GraphValidationException("Pipeline '" + graphName + "' node '" + nodeId
						+ "' zips two unrelated sequences: input '" + driverVia + "' comes from '" + driver
						+ "' while '" + binding.targetPortId() + "' comes from '" + candidate
						+ "'. Elements of different fan-outs have no meaningful correspondence - "
						+ "gather one of them with a sequence input first");
				}
			}

			ExecutionMode mode = driver == null ? ExecutionMode.SINGLE : ExecutionMode.PER_ELEMENT;
			node.setExecution(mode, driver);

			for (PortSpec out : own.outputs()) {
				boolean many = out.getCardinality() == Cardinality.MANY || mode == ExecutionMode.PER_ELEMENT;
				effectiveMany.put(key(nodeId, out.getId()), many);
				if (many && mode == ExecutionMode.PER_ELEMENT && out.getCardinality() == Cardinality.MANY) {
					// A per-element node that also declares a MANY output would nest one sequence
					// inside another, and a single integer seq cannot address that.
					throw new GraphValidationException("Pipeline '" + graphName + "' node '" + nodeId
						+ "' runs once per element and also emits the sequence '" + out.getId()
						+ "'. Nested fan-out is not supported - gather with a sequence input first");
				}
			}
		}
	}

	/**
	 * The fan-out a node's sequence output descends from: itself when it declares the {@code MANY}
	 * port, otherwise whatever drives it.
	 */
	private static String driverOf(Map<String, PipelineGraphNode> nodes, String nodeId) {
		PipelineGraphNode node = nodes.get(nodeId);
		if (node != null && node.getExecutionMode() == ExecutionMode.PER_ELEMENT && node.getFanOutDriver() != null) {
			return node.getFanOutDriver();
		}
		return nodeId;
	}

	private static String key(String nodeId, String portId) {
		return nodeId + " " + portId;
	}

	/**
	 * The demanded-output sets for a whole graph, ready to stamp onto nodes.
	 */
	public static List<String> demandedFor(Map<String, PipelineGraphNode> nodes, String nodeId) {
		return new ArrayList<>(demandedByNode(nodes).getOrDefault(nodeId, Set.of()));
	}
}
