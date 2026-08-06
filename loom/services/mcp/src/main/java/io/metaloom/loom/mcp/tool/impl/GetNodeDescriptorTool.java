package io.metaloom.loom.mcp.tool.impl;

import static io.metaloom.loom.mcp.tool.MCPToolResults.mcpTextResult;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.mcp.model.MCPToolDescriptor;
import io.metaloom.loom.mcp.model.MCPToolDescriptor.MCPToolParam;
import io.metaloom.loom.mcp.tool.MCPTool;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.NodeParameter;
import io.metaloom.loom.nodes.spec.PortGroup;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.nodes.spec.ResolvedPorts;
import io.metaloom.loom.rest.model.nodes.NodeAvailability;
import io.metaloom.loom.rest.service.impl.NodeAvailabilityService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

/**
 * MCP tool: get_node_descriptor
 *
 * <p>
 * Everything needed to place one node kind in a graph: its port ids and content types, its port groups, and the option keys it accepts. An edge is
 * addressed by port id, so this is what stands between a draft and {@code node 'pn3' has no input port 'media'}.
 * </p>
 *
 * <p>
 * 🔴 <b>Ports are resolved, not read off the descriptor.</b> {@code script}, {@code llm}, {@code vlm} and {@code filter} declare
 * {@code dynamicPorts} and derive their real ports from the instance options — a {@code script} node's outputs are whatever its {@code outputs} option
 * declares. This tool therefore takes the options the caller intends to use and calls
 * {@link NodeDescriptorRegistry#resolvePorts(String, Map)}, which is the same call {@code PortGraphAnalyzer} makes at save time. The REST descriptor
 * endpoint serves only the static descriptor, so this is the only place in the system that answers the question an author actually has.
 * </p>
 */
@Singleton
public class GetNodeDescriptorTool implements MCPTool {

	private final NodeDescriptorRegistry registry;

	private final NodeAvailabilityService availability;

	@Inject
	public GetNodeDescriptorTool(NodeDescriptorRegistry registry, NodeAvailabilityService availability) {
		this.registry = registry;
		this.availability = availability;
	}

	@Override
	public MCPToolDescriptor descriptor() {
		return new MCPToolDescriptor(
			"get_node_descriptor",
			"Load the full contract of one pipeline node kind: its input and output ports with their ids, content types and cardinality, "
				+ "its port groups, and the options it accepts. Call this for EVERY kind you intend to place before writing a definition — "
				+ "edges are addressed by port id and a guessed id is rejected. For kinds with dynamic ports (script, llm, vlm, filter) pass "
				+ "the options you intend to use so the real ports are resolved.",
			MCPToolDescriptor.buildInputSchema(List.of(
				new MCPToolParam("kind", "string", "The node kind, e.g. 'facedetect'", true),
				new MCPToolParam("options", "object", "The node options you intend to use; only affects kinds with dynamic ports", false))),
			List.of("READ_PIPELINE"));
	}

	@Override
	public Future<JsonObject> execute(JsonObject arguments) {
		try {
			String kind = arguments.getString("kind");
			if (kind == null || kind.isBlank()) {
				return Future.succeededFuture(mcpTextResult("ERROR: The kind parameter is required."));
			}
			kind = kind.trim();

			NodeDescriptor descriptor = registry.get(kind);
			if (descriptor == null) {
				return Future.succeededFuture(mcpTextResult("No node kind '" + kind
					+ "' exists in this Loom. Call list_node_descriptors to see what is available."));
			}

			JsonObject options = arguments.getJsonObject("options");
			ResolvedPorts ports = registry.resolvePorts(kind, options == null ? Map.of() : options.getMap());

			return Future.succeededFuture(mcpTextResult(describe(kind, descriptor, ports, options != null)));
		} catch (Exception e) {
			return Future.failedFuture(e);
		}
	}

	private String describe(String kind, NodeDescriptor descriptor, ResolvedPorts ports, boolean optionsGiven) {
		StringBuilder sb = new StringBuilder();
		sb.append("Node kind: ").append(kind).append("\n");
		if (descriptor.getName() != null) {
			sb.append("name: ").append(descriptor.getName()).append("\n");
		}
		sb.append("category: ").append(descriptor.getCategory() == null ? "ANALYSIS" : descriptor.getCategory().name()).append("\n");
		if (descriptor.getDescription() != null && !descriptor.getDescription().isBlank()) {
			sb.append("description: ").append(descriptor.getDescription()).append("\n");
		}
		sb.append("defaults: blocking=").append(descriptor.isDefaultBlocking())
			.append(", concurrency=").append(descriptor.getDefaultConcurrency())
			.append(", mode=").append(descriptor.getDefaultMode())
			.append("\n");

		NodeAvailability state = availability.availability(false).get(kind);
		if (state != null) {
			sb.append("available: ").append(state.isAvailable()
				? "yes — an online worker offers this kind"
				: "no — no online worker offers this kind right now; the pipeline can still be saved").append("\n");
		}

		if (descriptor.isDynamicPorts()) {
			sb.append(optionsGiven
				? "\nPorts below are RESOLVED from the options you passed — different options give different ports.\n"
				: "\nThis kind has DYNAMIC ports: the ones below are what it has with no options set. "
					+ "Call this tool again with your intended options to see the real ones.\n");
		}

		sb.append("\nInput ports:\n").append(portLines(ports == null ? null : ports.inputs(), true));
		sb.append("\nOutput ports:\n").append(portLines(ports == null ? null : ports.outputs(), false));

		String inputGroups = groupLines(ports == null ? null : ports.inputGroups());
		if (!inputGroups.isEmpty()) {
			sb.append("\nInput port groups:\n").append(inputGroups);
		}
		String outputGroups = groupLines(ports == null ? null : ports.outputGroups());
		if (!outputGroups.isEmpty()) {
			sb.append("\nOutput port groups:\n").append(outputGroups);
		}

		sb.append("\nOptions:\n").append(parameterLines(descriptor.getParameters()));
		return sb.toString();
	}

	private static String portLines(List<PortSpec> ports, boolean input) {
		if (ports == null || ports.isEmpty()) {
			return "  (none)\n";
		}
		StringBuilder sb = new StringBuilder();
		for (PortSpec port : ports) {
			sb.append("  - ").append(port.getId())
				.append(" : ").append(port.getContentType())
				.append(" ").append(port.getCardinality());
			if (input) {
				// The group owns required-ness for a grouped port, so reporting the port's own flag
				// there would contradict what the analyzer enforces.
				sb.append(port.getGroup() != null ? " (group " + port.getGroup() + ")" : port.isRequired() ? " (required)" : " (optional)");
			} else if (port.isSelective()) {
				sb.append(" (selective — a consumer wired here is skipped for items where nothing was emitted)");
			}
			if (port.getDescription() != null && !port.getDescription().isBlank()) {
				sb.append(" — ").append(port.getDescription().replaceAll("\\s+", " ").trim());
			}
			sb.append("\n");
		}
		return sb.toString();
	}

	private static String groupLines(List<PortGroup> groups) {
		if (groups == null || groups.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (PortGroup group : groups) {
			sb.append("  - ").append(group.getId()).append(" : ").append(group.getMode())
				.append(group.isRequired() ? " (exactly one member must be wired)" : " (at most one member may be wired)")
				.append("\n");
		}
		return sb.toString();
	}

	private static String parameterLines(List<NodeParameter> parameters) {
		if (parameters == null || parameters.isEmpty()) {
			return "  (none)\n";
		}
		StringBuilder sb = new StringBuilder();
		for (NodeParameter parameter : parameters) {
			sb.append("  - ").append(parameter.getKey()).append(" : ").append(parameter.getType());
			if (parameter.getDefaultValue() != null) {
				sb.append(" (default ").append(parameter.getDefaultValue()).append(")");
			}
			if (parameter.getValues() != null && !parameter.getValues().isEmpty()) {
				sb.append(" one of ").append(parameter.getValues());
			}
			if (parameter.getMin() != null || parameter.getMax() != null) {
				sb.append(" range ").append(parameter.getMin()).append("..").append(parameter.getMax());
			}
			if (parameter.getDescription() != null && !parameter.getDescription().isBlank()) {
				sb.append(" — ").append(parameter.getDescription().replaceAll("\\s+", " ").trim());
			}
			sb.append("\n");
		}
		return sb.toString();
	}

}
