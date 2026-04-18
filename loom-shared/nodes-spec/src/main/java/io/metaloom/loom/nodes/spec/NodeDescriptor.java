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

	@JsonPropertyDescription("Input connectors this node accepts")
	private List<NodeInput> inputs = new ArrayList<>();

	@JsonPropertyDescription("Output connectors this node produces")
	private List<NodeOutput> outputs = new ArrayList<>();

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

	public List<NodeInput> getInputs() {
		return inputs;
	}

	public NodeDescriptor setInputs(List<NodeInput> inputs) {
		this.inputs = inputs;
		return this;
	}

	public List<NodeOutput> getOutputs() {
		return outputs;
	}

	public NodeDescriptor setOutputs(List<NodeOutput> outputs) {
		this.outputs = outputs;
		return this;
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
