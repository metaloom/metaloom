package io.metaloom.loom.nodes.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared base for the prompt-driven model nodes: one output port per configured prompt.
 *
 * <p>
 * Both {@code llm} and {@code vlm} keep their prompts in a {@code prompts} option keyed by prompt id
 * and emit one result per prompt. Their descriptors used to declare a single {@code llm_result} /
 * {@code vlm_result} output while the nodes actually wrote {@code llm_result_<promptId>} - a name
 * nothing downstream could ever have bound to. Deriving the ports from the very option that drives
 * the node is what closes that gap for good.
 * </p>
 */
public abstract class PromptPortResolver implements NodePortResolver {

	/** The option holding the prompt map, keyed by prompt id. */
	static final String OPTION_PROMPTS = "prompts";

	/** Port id used when no prompt is configured yet, so the node is still connectable. */
	static final String FALLBACK_PORT_ID = "result";

	/** Prefix of a per-prompt port id, matching what the node writes at runtime. */
	static final String PORT_PREFIX = "result_";

	/**
	 * How the model reading these prompts is described in the port tooltips.
	 */
	protected abstract String modelLabel();

	@Override
	public List<PortSpec> resolveOutputPorts(NodeDescriptor descriptor, Map<String, Object> options) {
		List<PortSpec> ports = new ArrayList<>();

		Object prompts = options == null ? null : options.get(OPTION_PROMPTS);
		if (prompts instanceof Map<?, ?> map) {
			for (Object key : map.keySet()) {
				if (!(key instanceof String raw)) {
					continue;
				}
				String promptId = raw.trim();
				// A blank id would produce the port "result_", which is well-formed but means nothing.
				if (promptId.isEmpty()) {
					continue;
				}
				String portId = PORT_PREFIX + promptId;
				if (!portId.matches(PortSpec.ID_PATTERN)) {
					continue;
				}
				ports.add(PortSpec.one(portId, ContentTypeRegistry.TEXT_PLAIN)
					.describedAs(promptId, "What " + modelLabel() + " answered for the '" + promptId + "' prompt"));
			}
		}

		if (ports.isEmpty()) {
			// No prompts configured (or the option was malformed): still offer one handle, otherwise
			// the node could not be wired up at all and the author would have no way back.
			ports.add(PortSpec.one(FALLBACK_PORT_ID, ContentTypeRegistry.TEXT_PLAIN)
				.describedAs("Result", "What " + modelLabel() + " answered. Configure prompts to get one port per prompt"));
		}
		return ports;
	}
}
