package io.metaloom.loom.nodes.spec;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Describes an output connector on a pipeline node.
 */
public class NodeOutput {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Name of this output (e.g. 'sha512', 'face_count')")
	private String name;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Content type this output produces (e.g. 'data/hash', 'data/integer')")
	private String contentType;

	public NodeOutput() {
	}

	public NodeOutput(String name, String contentType) {
		this.name = name;
		this.contentType = contentType;
	}

	public String getName() {
		return name;
	}

	public NodeOutput setName(String name) {
		this.name = name;
		return this;
	}

	public String getContentType() {
		return contentType;
	}

	public NodeOutput setContentType(String contentType) {
		this.contentType = contentType;
		return this;
	}
}
