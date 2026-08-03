package io.metaloom.cortex.node.metadata;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The fifteen elements of the Dublin Core Metadata Element Set, as the envelope carries them.
 *
 * <p>
 * {@link #creator}, {@link #subject}, {@link #contributor} and {@link #relation} are <b>always
 * arrays</b> and everything else is always scalar. Consumers rely on that: a field that is sometimes
 * a string and sometimes a list is the single most common source of downstream breakage.
 * </p>
 *
 * <p>
 * Two terms are easy to get backwards. {@link #type} is a term from the DCMI Type vocabulary -
 * {@code StillImage}, {@code MovingImage}, {@code Sound}, {@code Text} - and {@link #format} is the
 * MIME type.
 * </p>
 */
public class DcBlock {

	private String title;
	private final List<String> creator = new ArrayList<>();
	private final List<String> subject = new ArrayList<>();
	private String description;
	private String publisher;
	private final List<String> contributor = new ArrayList<>();
	private String date;
	private String type;
	private String format;
	private String identifier;
	private String source;
	private String language;
	private final List<String> relation = new ArrayList<>();
	private String coverage;
	private String rights;

	public String getTitle() {
		return title;
	}

	public DcBlock setTitle(String title) {
		this.title = title;
		return this;
	}

	public List<String> getCreator() {
		return creator;
	}

	public List<String> getSubject() {
		return subject;
	}

	public String getDescription() {
		return description;
	}

	public DcBlock setDescription(String description) {
		this.description = description;
		return this;
	}

	public String getPublisher() {
		return publisher;
	}

	public DcBlock setPublisher(String publisher) {
		this.publisher = publisher;
		return this;
	}

	public List<String> getContributor() {
		return contributor;
	}

	public String getDate() {
		return date;
	}

	public DcBlock setDate(String date) {
		this.date = date;
		return this;
	}

	public String getType() {
		return type;
	}

	public DcBlock setType(String type) {
		this.type = type;
		return this;
	}

	public String getFormat() {
		return format;
	}

	public DcBlock setFormat(String format) {
		this.format = format;
		return this;
	}

	public String getIdentifier() {
		return identifier;
	}

	public DcBlock setIdentifier(String identifier) {
		this.identifier = identifier;
		return this;
	}

	public String getSource() {
		return source;
	}

	public DcBlock setSource(String source) {
		this.source = source;
		return this;
	}

	public String getLanguage() {
		return language;
	}

	public DcBlock setLanguage(String language) {
		this.language = language;
		return this;
	}

	public List<String> getRelation() {
		return relation;
	}

	public String getCoverage() {
		return coverage;
	}

	public DcBlock setCoverage(String coverage) {
		this.coverage = coverage;
		return this;
	}

	public String getRights() {
		return rights;
	}

	public DcBlock setRights(String rights) {
		this.rights = rights;
		return this;
	}

	public boolean isEmpty() {
		return title == null && description == null && publisher == null && date == null && type == null
			&& format == null && identifier == null && source == null && language == null && coverage == null
			&& rights == null && creator.isEmpty() && subject.isEmpty() && contributor.isEmpty() && relation.isEmpty();
	}

	/**
	 * Serialise the block. The four list-valued elements are always present as arrays - possibly
	 * empty - and the scalars are omitted when the file did not carry them.
	 */
	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		Envelopes.putIfPresent(json, "title", title);
		json.put("creator", new JsonArray(List.copyOf(creator)));
		json.put("subject", new JsonArray(List.copyOf(subject)));
		Envelopes.putIfPresent(json, "description", description);
		Envelopes.putIfPresent(json, "publisher", publisher);
		json.put("contributor", new JsonArray(List.copyOf(contributor)));
		Envelopes.putIfPresent(json, "date", date);
		Envelopes.putIfPresent(json, "type", type);
		Envelopes.putIfPresent(json, "format", format);
		Envelopes.putIfPresent(json, "identifier", identifier);
		Envelopes.putIfPresent(json, "source", source);
		Envelopes.putIfPresent(json, "language", language);
		json.put("relation", new JsonArray(List.copyOf(relation)));
		Envelopes.putIfPresent(json, "coverage", coverage);
		Envelopes.putIfPresent(json, "rights", rights);
		return json;
	}
}
