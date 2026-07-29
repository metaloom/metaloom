package io.metaloom.loom.nodes.spec;

import java.util.List;
import java.util.Map;

/**
 * Derives a node instance's ports from its configured options.
 *
 * <p>
 * Most kinds have a fixed port set that lives on the {@link NodeDescriptor}. Three do not, and for the same structural reason: their output set
 * <em>is</em> configuration. A {@code script} node's outputs are declared in its options; {@code llm} and {@code vlm} emit one result per configured
 * prompt. The editor has to draw those handles <strong>before the node has ever run</strong> — a node whose ports only existed after execution would
 * be unconnectable.
 * </p>
 *
 * <p>
 * Implementations are discovered through {@link java.util.ServiceLoader} and applied only to descriptors that set
 * {@link NodeDescriptor#isDynamicPorts()}. The server-side resolver is authoritative at save time; the editor keeps a TypeScript mirror purely so
 * handles appear as the author types.
 * </p>
 */
public interface NodePortResolver {

	/**
	 * The node kind this resolver applies to.
	 */
	String kind();

	/**
	 * Resolve the output ports for a node instance.
	 *
	 * @param descriptor
	 *            the static descriptor for {@link #kind()}
	 * @param options
	 *            the node instance's configured options; never null, may be empty
	 * @return the full output port list for this instance
	 */
	List<PortSpec> resolveOutputPorts(NodeDescriptor descriptor, Map<String, Object> options);

	/**
	 * Resolve the input ports for a node instance. Defaults to the descriptor's static inputs.
	 */
	default List<PortSpec> resolveInputPorts(NodeDescriptor descriptor, Map<String, Object> options) {
		return descriptor.getInputPorts();
	}
}
