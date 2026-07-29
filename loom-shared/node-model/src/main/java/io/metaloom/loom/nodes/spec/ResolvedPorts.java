package io.metaloom.loom.nodes.spec;

import java.util.List;

/**
 * A node instance's effective ports — the descriptor's static ports, or what a {@link NodePortResolver} derived from its options.
 *
 * <p>
 * Validation and the graph parser always work against this, never against {@link NodeDescriptor} directly, so a {@code script} node's per-instance
 * outputs are checked exactly like a fixed kind's.
 * </p>
 */
public record ResolvedPorts(List<PortSpec> inputs, List<PortSpec> outputs, List<PortGroup> inputGroups, List<PortGroup> outputGroups) {

	/**
	 * Look up an input port by id.
	 *
	 * @return the port, or {@code null} when the instance has no such input
	 */
	public PortSpec input(String portId) {
		return find(inputs, portId);
	}

	/**
	 * Look up an output port by id.
	 *
	 * @return the port, or {@code null} when the instance has no such output
	 */
	public PortSpec output(String portId) {
		return find(outputs, portId);
	}

	/**
	 * Look up an input group by id.
	 */
	public PortGroup inputGroup(String groupId) {
		if (groupId == null) {
			return null;
		}
		return inputGroups.stream().filter(g -> groupId.equals(g.getId())).findFirst().orElse(null);
	}

	/**
	 * Look up an output group by id.
	 */
	public PortGroup outputGroup(String groupId) {
		if (groupId == null) {
			return null;
		}
		return outputGroups.stream().filter(g -> groupId.equals(g.getId())).findFirst().orElse(null);
	}

	/**
	 * The input ports that belong to the given group.
	 */
	public List<PortSpec> inputsInGroup(String groupId) {
		return inputs.stream().filter(p -> groupId.equals(p.getGroup())).toList();
	}

	/**
	 * The output ports that belong to the given group.
	 */
	public List<PortSpec> outputsInGroup(String groupId) {
		return outputs.stream().filter(p -> groupId.equals(p.getGroup())).toList();
	}

	private static PortSpec find(List<PortSpec> ports, String portId) {
		if (portId == null) {
			return null;
		}
		return ports.stream().filter(p -> portId.equals(p.getId())).findFirst().orElse(null);
	}
}
