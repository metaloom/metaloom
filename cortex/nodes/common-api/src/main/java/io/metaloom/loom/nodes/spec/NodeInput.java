package io.metaloom.loom.nodes.spec;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Describes an input connector on a pipeline node.
 */
public class NodeInput {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Name of this input (e.g. 'media')")
	private String name;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Content type this input accepts (e.g. 'media/image', 'media/*')")
	private String contentType;

	@JsonPropertyDescription("Whether this input must be connected for the node to execute")
	private boolean required = true;

	public NodeInput() {
	}

	public NodeInput(String name, String contentType, boolean required) {
		this.name = name;
		this.contentType = contentType;
		this.required = required;
	}

	public String getName() {
		return name;
	}

	public NodeInput setName(String name) {
		this.name = name;
		return this;
	}

	public String getContentType() {
		return contentType;
	}

	public NodeInput setContentType(String contentType) {
		this.contentType = contentType;
		return this;
	}

	public boolean isRequired() {
		return required;
	}

	public NodeInput setRequired(boolean required) {
		this.required = required;
		return this;
	}
}
