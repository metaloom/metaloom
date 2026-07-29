package io.metaloom.loom.nodes.spec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A set of ports that are alternatives ({@link PortGroupMode#XOR}) or mutually exclusive ({@link PortGroupMode#EXCLUSIVE}).
 *
 * <p>
 * A member port does not carry its own {@code required} flag — the group owns it.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortGroup {

	@JsonProperty(required = true)
	@JsonPropertyDescription("Group identifier, referenced by PortSpec.group")
	private String id;

	@JsonProperty(required = true)
	@JsonPropertyDescription("How the members relate: XOR (input alternatives) or EXCLUSIVE (mutually exclusive outputs)")
	private PortGroupMode mode;

	@JsonPropertyDescription("Whether exactly one member must be wired (XOR groups only)")
	private boolean required = true;

	@JsonPropertyDescription("Human-readable label shown by the editor")
	private String label;

	public PortGroup() {
	}

	public PortGroup(String id, PortGroupMode mode, boolean required, String label) {
		this.id = id;
		this.mode = mode;
		this.required = required;
		this.label = label;
	}

	/**
	 * A required set of input alternatives — exactly one must be wired.
	 */
	public static PortGroup xor(String id, String label) {
		return new PortGroup(id, PortGroupMode.XOR, true, label);
	}

	/**
	 * An optional set of input alternatives — at most one may be wired.
	 */
	public static PortGroup optionalXor(String id, String label) {
		return new PortGroup(id, PortGroupMode.XOR, false, label);
	}

	/**
	 * A set of mutually exclusive outputs — at most one may be wired.
	 */
	public static PortGroup exclusive(String id, String label) {
		return new PortGroup(id, PortGroupMode.EXCLUSIVE, false, label);
	}

	public String getId() {
		return id;
	}

	public PortGroup setId(String id) {
		this.id = id;
		return this;
	}

	public PortGroupMode getMode() {
		return mode;
	}

	public PortGroup setMode(PortGroupMode mode) {
		this.mode = mode;
		return this;
	}

	public boolean isRequired() {
		return required;
	}

	public PortGroup setRequired(boolean required) {
		this.required = required;
		return this;
	}

	public String getLabel() {
		return label;
	}

	public PortGroup setLabel(String label) {
		this.label = label;
		return this;
	}

	@Override
	public String toString() {
		return id + "[" + mode + (required ? ", required" : "") + "]";
	}
}
