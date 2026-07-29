package io.metaloom.loom.nodes.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * One entry of the content-type vocabulary, as served to the UI.
 *
 * <p>
 * The former {@code superType} field is gone. It was a parent pointer that <em>no Java code ever read</em>, and it is unnecessary: the supertype of
 * {@code detection/face} is structurally {@code detection/*}, which {@link ContentTypeLattice} derives from the id. What the UI needs instead is the
 * {@link #getFamily() family}, because that is what it colours by.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentType {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Unique content type identifier, always 'family/subtype' (e.g. 'media/image', 'detection/face')")
	private String id;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Human-readable label")
	private String label;

	@JsonProperty(required = true)
	@JsonPropertyDescription("The family part of the id - the editor's colour key (e.g. 'media', 'detection')")
	private String family;

	@JsonPropertyDescription("What this type carries, shown by the editor on hover")
	private String description;

	@JsonPropertyDescription("Whether this is the family wildcard (e.g. 'media/*')")
	private boolean wildcard;

	public ContentType() {
	}

	public ContentType(String id, String label, String description) {
		this.id = id;
		this.label = label;
		this.description = description;
		this.family = ContentTypeLattice.family(id);
		this.wildcard = ContentTypeLattice.isWildcard(id);
	}

	public String getId() {
		return id;
	}

	public ContentType setId(String id) {
		this.id = id;
		this.family = ContentTypeLattice.family(id);
		this.wildcard = ContentTypeLattice.isWildcard(id);
		return this;
	}

	public String getLabel() {
		return label;
	}

	public ContentType setLabel(String label) {
		this.label = label;
		return this;
	}

	public String getFamily() {
		return family;
	}

	public ContentType setFamily(String family) {
		this.family = family;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public ContentType setDescription(String description) {
		this.description = description;
		return this;
	}

	public boolean isWildcard() {
		return wildcard;
	}

	public ContentType setWildcard(boolean wildcard) {
		this.wildcard = wildcard;
		return this;
	}

	@Override
	public String toString() {
		return id;
	}
}
