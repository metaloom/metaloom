package io.metaloom.loom.nodes.spec;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Describes a content type that can flow between pipeline nodes.
 * Content types are used for connector compatibility validation.
 */
public class ContentType {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Unique content type identifier (e.g. 'media/image', 'data/hash')")
	private String id;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Human-readable label")
	private String label;

	@JsonPropertyDescription("Parent content type for wildcard matching (e.g. 'media/*' is superType of 'media/image')")
	private String superType;

	public ContentType() {
	}

	public ContentType(String id, String label) {
		this.id = id;
		this.label = label;
	}

	public ContentType(String id, String label, String superType) {
		this.id = id;
		this.label = label;
		this.superType = superType;
	}

	public String getId() {
		return id;
	}

	public ContentType setId(String id) {
		this.id = id;
		return this;
	}

	public String getLabel() {
		return label;
	}

	public ContentType setLabel(String label) {
		this.label = label;
		return this;
	}

	public String getSuperType() {
		return superType;
	}

	public ContentType setSuperType(String superType) {
		this.superType = superType;
		return this;
	}
}
