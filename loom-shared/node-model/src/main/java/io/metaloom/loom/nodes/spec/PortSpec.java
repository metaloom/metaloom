package io.metaloom.loom.nodes.spec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * A typed connector on a pipeline node — one input or one output.
 *
 * <p>
 * Replaces the former {@code NodeInput} / {@code NodeOutput} pair, which carried only a name and a content-type string, were read by no backend code
 * at all, and had no notion of cardinality.
 * </p>
 *
 * <p>
 * The port {@code id} is the stable identity: edges reference it as {@code sourcePort}/{@code targetPort}, the editor uses it as the React Flow handle
 * id, and the node addresses its data by it. It is <strong>not</strong> positional — reordering a node's ports never re-points an existing edge.
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortSpec {

	/** Port ids share the shape of node output keys: lowercase, digits and underscore. */
	public static final String ID_PATTERN = "^[a-z0-9][a-z0-9_]{0,62}$";

	@JsonProperty(required = true)
	@JsonPropertyDescription("Port identifier, unique among the node's inputs (or outputs)")
	private String id;

	@JsonPropertyDescription("Human-readable label shown by the editor")
	private String label;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Content type carried by this port (e.g. 'detection/face', 'media/*')")
	private String contentType;

	@JsonProperty(required = true)
	@JsonPropertyDescription("Whether this port carries one element or a sequence")
	private Cardinality cardinality = Cardinality.ONE;

	@JsonPropertyDescription("Whether this input must be wired for the node to execute. Ignored for grouped ports - the group owns it")
	private boolean required = true;

	/**
	 * Output side only. A selective port is how a node expresses a branch: it carries data for some items and not others, and the engine skips a
	 * consumer wired to it for the items where nothing was emitted.
	 *
	 * <p>
	 * This is deliberately opt-in per port rather than a property of the node. Leaving a declared output unwritten is normal and must stay harmless -
	 * {@code facedetect} finds no faces, a {@code script} does not set every declared key - so a blanket "unwritten port stops the branch" rule would
	 * silently change what those graphs do. Only a port that says it routes, routes.
	 * </p>
	 *
	 * <p>
	 * {@code NON_DEFAULT} is on the field because the class-level {@code NON_NULL} does not suppress a primitive {@code false}: without it every one of
	 * the ~150 ports in the generated descriptor snapshots would grow a {@code "selective": false} line.
	 * </p>
	 */
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	@JsonPropertyDescription("Output side only: this port carries data for some items and not others. A consumer wired to it is skipped for the items "
		+ "where nothing was emitted")
	private boolean selective = false;

	@JsonPropertyDescription("Id of the PortGroup this port belongs to, if any")
	private String group;

	@JsonPropertyDescription("What this port carries, shown by the editor on hover")
	private String description;

	public PortSpec() {
	}

	public PortSpec(String id, String contentType, Cardinality cardinality) {
		this.id = id;
		this.contentType = contentType;
		this.cardinality = cardinality;
	}

	/** A required port carrying exactly one element. */
	public static PortSpec one(String id, String contentType) {
		return new PortSpec(id, contentType, Cardinality.ONE);
	}

	/** A required port carrying a sequence of elements. */
	public static PortSpec many(String id, String contentType) {
		return new PortSpec(id, contentType, Cardinality.MANY);
	}

	/** An optional port carrying exactly one element. */
	public static PortSpec optionalOne(String id, String contentType) {
		return new PortSpec(id, contentType, Cardinality.ONE).setRequired(false);
	}

	/** An optional port carrying a sequence of elements. */
	public static PortSpec optionalMany(String id, String contentType) {
		return new PortSpec(id, contentType, Cardinality.MANY).setRequired(false);
	}

	/**
	 * A required output port carrying one element <em>for the items it routes</em>. See {@link #selective}.
	 */
	public static PortSpec selectiveOne(String id, String contentType) {
		return new PortSpec(id, contentType, Cardinality.ONE).setSelective(true);
	}

	public String getId() {
		return id;
	}

	public PortSpec setId(String id) {
		this.id = id;
		return this;
	}

	public String getLabel() {
		return label;
	}

	public PortSpec setLabel(String label) {
		this.label = label;
		return this;
	}

	public String getContentType() {
		return contentType;
	}

	public PortSpec setContentType(String contentType) {
		this.contentType = contentType;
		return this;
	}

	public Cardinality getCardinality() {
		return cardinality;
	}

	public PortSpec setCardinality(Cardinality cardinality) {
		this.cardinality = cardinality;
		return this;
	}

	public boolean isRequired() {
		return required;
	}

	public PortSpec setRequired(boolean required) {
		this.required = required;
		return this;
	}

	public boolean isSelective() {
		return selective;
	}

	public PortSpec setSelective(boolean selective) {
		this.selective = selective;
		return this;
	}

	public String getGroup() {
		return group;
	}

	public PortSpec setGroup(String group) {
		this.group = group;
		return this;
	}

	public String getDescription() {
		return description;
	}

	public PortSpec setDescription(String description) {
		this.description = description;
		return this;
	}

	/** Convenience: assign this port to a group in a fluent chain. */
	public PortSpec inGroup(String groupId) {
		this.group = groupId;
		return this;
	}

	/** Convenience: give this port a label and description in a fluent chain. */
	public PortSpec describedAs(String label, String description) {
		this.label = label;
		this.description = description;
		return this;
	}

	/**
	 * Whether this port carries a sequence — derived from {@link #getCardinality()}.
	 *
	 * <p>
	 * {@code @JsonIgnore} because there is no {@code setMany}: these types were only ever
	 * <em>serialized</em>, so nothing noticed. A worker now announces descriptors that Loom must
	 * <em>deserialize</em>, and an emitted {@code "many"} with no setter fails the first
	 * {@code readValue}. It is redundant with {@code cardinality}, which both the editor and the engine
	 * read instead — so drop it from the wire rather than loosening deserialization globally.
	 * </p>
	 */
	@JsonIgnore
	public boolean isMany() {
		return cardinality != null && cardinality.isMany();
	}

	@Override
	public String toString() {
		return id + " : " + contentType + " " + cardinality;
	}
}
