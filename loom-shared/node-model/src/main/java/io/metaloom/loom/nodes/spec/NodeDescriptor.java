package io.metaloom.loom.nodes.spec;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Descriptor that fully describes a pipeline node kind to the UI.
 * The UI uses this to render the node palette, generate edit forms,
 * validate connections, and display live status indicators.
 */
public class NodeDescriptor {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Unique machine-readable kind identifier (e.g. 'facedetect', 'sha512')")
	private String kind;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Human-readable display name")
	private String name;

	@JsonPropertyDescription("Description of what this node does")
	private String description;

	@JsonPropertyDescription("Icon key for the UI (e.g. material-icons name)")
	private String icon;

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

	public String getKind() {
		return kind;
	}

	public NodeDescriptor setKind(String kind) {
		this.kind = kind;
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
