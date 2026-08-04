package io.metaloom.loom.nodes.spec;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Descriptor that fully describes a pipeline node type to the UI.
 * The UI uses this to render the node palette, generate edit forms,
 * validate connections, and display live status indicators.
 *
 * <p>
 * This is <strong>the</strong> contract type. The same object is what a Cortex worker announces over
 * {@code NODE_REGISTRATION}, what {@code /api/v1/pipeline/node-descriptors} serves, what the checked-in
 * {@code node-descriptors.json} snapshot holds, and what {@code PortGraphAnalyzer} validates against.
 * Do not add a second, parallel registration shape — and do not add runtime fleet state (availability,
 * which worker offers it) here: that is served in a sibling block, keyed by {@link #getNodeId()}.
 * </p>
 */
@JsonPropertyOrder({ "nodeId", "kind", "version", "name" })
public class NodeDescriptor {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Unique machine-readable node type identifier (e.g. 'facedetect', 'sha512')")
	private String nodeId;

	/**
	 * Contract version, as announced by the worker that offers this node.
	 *
	 * <p>
	 * {@code null} for built-in descriptors and for workers that declare no version — an unversioned
	 * contract, which disables version-skew detection rather than guessing an order.
	 * </p>
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Contract version of this node type, e.g. '1.0.0-SNAPSHOT'. Null means unversioned")
	private String version;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Human-readable display name")
	private String name;

	@JsonPropertyDescription("Description of what this node does")
	private String description;

	@JsonPropertyDescription("Icon key for the UI (e.g. material-icons name)")
	private String icon;

	/**
	 * Card colour authored on the node's {@code @NodeSpec}, or {@code null} — the normal case, meaning
	 * the node takes its {@link #getCategory()} default.
	 *
	 * <p>
	 * Always a {@code #rgb} / {@code #rrggbb} literal: {@link #setColor(String)} drops anything else,
	 * including on the deserialization path, so a consumer may write this straight into a style
	 * attribute. See that setter for why.
	 * </p>
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonPropertyDescription("Node card colour as a #rgb/#rrggbb hex literal. Null means: use the category default")
	private String color;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Category for palette grouping")
	private NodeCategory category;

	@JsonPropertyDescription("Typed input ports this node accepts")
	private List<PortSpec> inputPorts = new ArrayList<>();

	@JsonPropertyDescription("Typed output ports this node produces")
	private List<PortSpec> outputPorts = new ArrayList<>();

	@JsonPropertyDescription("Groups over the input ports (XOR alternatives)")
	private List<PortGroup> inputGroups = new ArrayList<>();

	@JsonPropertyDescription("Groups over the output ports (EXCLUSIVE selections)")
	private List<PortGroup> outputGroups = new ArrayList<>();

	@JsonPropertyDescription("Whether this kind derives extra ports from its options via a NodePortResolver")
	private boolean dynamicPorts = false;

	@JsonPropertyDescription("Configurable parameters (drives the edit form)")
	private List<NodeParameter> parameters = new ArrayList<>();

	@JsonPropertyDescription("Default max concurrent executions")
	private int defaultConcurrency = 1;

	@JsonPropertyDescription("Default execution mode")
	private NodeMode defaultMode = NodeMode.PARALLEL;

	@JsonPropertyDescription("Whether downstream nodes block on this node by default")
	private boolean defaultBlocking = true;

	@JsonPropertyDescription("Events the UI can visualise for this node")
	private List<String> events = new ArrayList<>();

	public NodeDescriptor() {
	}

	public String getNodeId() {
		return nodeId;
	}

	public NodeDescriptor setNodeId(String nodeId) {
		this.nodeId = nodeId;
		return this;
	}

	/**
	 * The former name of {@link #getNodeId()}.
	 *
	 * <p>
	 * Emitted <em>and</em> accepted alongside {@code nodeId} for one release so the checked-in
	 * {@code website/static/pipeline-editor/node-descriptors.json}, the TypeScript mirror in
	 * {@code loom-ui/src/types/nodeDescriptors.ts} and the offline website editor do not all break in
	 * the same commit. Both names read and write the one field, so a payload carrying either — or both,
	 * agreeing — deserializes identically.
	 * </p>
	 *
	 * @deprecated use {@link #getNodeId()}
	 */
	@Deprecated
	@JsonProperty("kind")
	@JsonPropertyDescription("Deprecated alias of nodeId, emitted for one release")
	public String getKind() {
		return nodeId;
	}

	/**
	 * @deprecated use {@link #setNodeId(String)}
	 */
	@Deprecated
	@JsonProperty("kind")
	public NodeDescriptor setKind(String kind) {
		this.nodeId = kind;
		return this;
	}

	public String getVersion() {
		return version;
	}

	public NodeDescriptor setVersion(String version) {
		this.version = version;
		return this;
	}

	public String getName() {
		return name;
	}

	public NodeDescriptor setName(String name) {
		this.name = name;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public NodeDescriptor setDescription(String description) {
		this.description = description;
		return this;
	}

	public String getIcon() {
		return icon;
	}

	public NodeDescriptor setIcon(String icon) {
		this.icon = icon;
		return this;
	}

	public String getColor() {
		return color;
	}

	/**
	 * Set the node's card colour, keeping it only if it is a {@code #rgb} / {@code #rrggbb} literal.
	 *
	 * <p>
	 * The filter lives in the setter rather than at the call sites because Jackson deserializes
	 * through it, so it also covers the path that matters most: a descriptor announced by a
	 * third-party worker over {@code NODE_REGISTRATION}. Both editors write this value into a style
	 * attribute, and an announced node is not trusted to choose what goes there — the same reason
	 * {@code icon} resolves against a compile-time map instead of being rendered as given.
	 * </p>
	 *
	 * <p>
	 * Rejection is silent-but-degraded on purpose: the node keeps working and simply falls back to its
	 * category colour. Refusing the whole descriptor would take a node offline over a typo in a
	 * cosmetic field.
	 * </p>
	 */
	public NodeDescriptor setColor(String color) {
		this.color = isHexColor(color) ? color : null;
		return this;
	}

	/** Whether {@code value} is a bare {@code #rgb} or {@code #rrggbb} literal and nothing else. */
	public static boolean isHexColor(String value) {
		if (value == null) {
			return false;
		}
		int length = value.length();
		if ((length != 4 && length != 7) || value.charAt(0) != '#') {
			return false;
		}
		for (int i = 1; i < length; i++) {
			char c = value.charAt(i);
			boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
			if (!hex) {
				return false;
			}
		}
		return true;
	}

	public NodeCategory getCategory() {
		return category;
	}

	public NodeDescriptor setCategory(NodeCategory category) {
		this.category = category;
		return this;
	}

	public List<PortSpec> getInputPorts() {
		return inputPorts;
	}

	public NodeDescriptor setInputPorts(List<PortSpec> inputPorts) {
		this.inputPorts = inputPorts;
		return this;
	}

	public List<PortSpec> getOutputPorts() {
		return outputPorts;
	}

	public NodeDescriptor setOutputPorts(List<PortSpec> outputPorts) {
		this.outputPorts = outputPorts;
		return this;
	}

	public List<PortGroup> getInputGroups() {
		return inputGroups;
	}

	public NodeDescriptor setInputGroups(List<PortGroup> inputGroups) {
		this.inputGroups = inputGroups;
		return this;
	}

	public List<PortGroup> getOutputGroups() {
		return outputGroups;
	}

	public NodeDescriptor setOutputGroups(List<PortGroup> outputGroups) {
		this.outputGroups = outputGroups;
		return this;
	}

	public boolean isDynamicPorts() {
		return dynamicPorts;
	}

	public NodeDescriptor setDynamicPorts(boolean dynamicPorts) {
		this.dynamicPorts = dynamicPorts;
		return this;
	}

	/**
	 * Look up an input port by id.
	 *
	 * @return the port, or {@code null} when this kind declares no such input
	 */
	public PortSpec inputPort(String portId) {
		return find(inputPorts, portId);
	}

	/**
	 * Look up an output port by id.
	 *
	 * @return the port, or {@code null} when this kind declares no such output
	 */
	public PortSpec outputPort(String portId) {
		return find(outputPorts, portId);
	}

	/**
	 * Look up an input group by id.
	 */
	public PortGroup inputGroup(String groupId) {
		if (groupId == null || inputGroups == null) {
			return null;
		}
		return inputGroups.stream().filter(g -> groupId.equals(g.getId())).findFirst().orElse(null);
	}

	/**
	 * Look up an output group by id.
	 */
	public PortGroup outputGroup(String groupId) {
		if (groupId == null || outputGroups == null) {
			return null;
		}
		return outputGroups.stream().filter(g -> groupId.equals(g.getId())).findFirst().orElse(null);
	}

	private static PortSpec find(List<PortSpec> ports, String portId) {
		if (portId == null || ports == null) {
			return null;
		}
		return ports.stream().filter(p -> portId.equals(p.getId())).findFirst().orElse(null);
	}

	public List<NodeParameter> getParameters() {
		return parameters;
	}

	public NodeDescriptor setParameters(List<NodeParameter> parameters) {
		this.parameters = parameters;
		return this;
	}

	public int getDefaultConcurrency() {
		return defaultConcurrency;
	}

	public NodeDescriptor setDefaultConcurrency(int defaultConcurrency) {
		this.defaultConcurrency = defaultConcurrency;
		return this;
	}

	public NodeMode getDefaultMode() {
		return defaultMode;
	}

	public NodeDescriptor setDefaultMode(NodeMode defaultMode) {
		this.defaultMode = defaultMode;
		return this;
	}

	public boolean isDefaultBlocking() {
		return defaultBlocking;
	}

	public NodeDescriptor setDefaultBlocking(boolean defaultBlocking) {
		this.defaultBlocking = defaultBlocking;
		return this;
	}

	public List<String> getEvents() {
		return events;
	}

	public NodeDescriptor setEvents(List<String> events) {
		this.events = events;
		return this;
	}
}
