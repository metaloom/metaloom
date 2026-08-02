package io.metaloom.cortex.node.filter;

import java.util.List;
import java.util.Locale;

import io.vertx.core.json.JsonObject;

/**
 * One configured branch of a {@link FilterNode}.
 *
 * <p>
 * The {@link #id()} is <strong>the output port id</strong>, which is what makes an N-way branch
 * expressible without any new vocabulary: the pipeline author draws an edge from the port they care
 * about and the engine does the rest. It must therefore match {@code PortSpec.ID_PATTERN}, and
 * {@code FilterPortResolver} silently drops a row that does not — a half-typed row is the normal
 * state of the editor while someone is typing in it.
 * </p>
 *
 * @param id
 *            the port id, lowercase letters, digits and underscore
 * @param label
 *            a human-readable name; falls back to {@code id}
 * @param match
 *            free-form hints for the strategy. For {@link FilterBy#LANGUAGE} these are alternative
 *            names the model may answer with ("german, deutsch"); a later mime-type strategy would
 *            read them as globs. Keeping it strategy-agnostic is what stops each new
 *            {@code filterBy} value needing its own option
 */
public record FilterBucket(String id, String label, String match) {

	/** Ids that would collide with the node's fixed ports or its own inputs. */
	public static final List<String> RESERVED = List.of("other", "passed", "bucket", "media", "text");

	/** Mirrors {@code PortSpec.ID_PATTERN}; duplicated because cortex must not depend on the editor's copy. */
	public static final String ID_PATTERN = "^[a-z0-9][a-z0-9_]{0,62}$";

	public FilterBucket {
		label = label == null || label.isBlank() ? id : label.trim();
	}

	public static FilterBucket from(JsonObject json) {
		return new FilterBucket(trimmed(json.getString("id")), trimmed(json.getString("label")), trimmed(json.getString("match")));
	}

	private static String trimmed(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	public boolean isValid() {
		return id != null && id.matches(ID_PATTERN) && !RESERVED.contains(id);
	}

	/**
	 * How this bucket is offered to a language model: the id it must answer with, plus whatever
	 * alternative names the author supplied.
	 */
	public String describeForPrompt() {
		StringBuilder text = new StringBuilder(id);
		if (!label.equals(id)) {
			text.append(" (").append(label).append(')');
		}
		if (match != null) {
			text.append(" - also known as: ").append(match);
		}
		return text.toString();
	}

	/**
	 * Whether a model's free-text answer names this bucket. Checked before falling back to
	 * {@code other}, so "German" and "deutsch" both land on the {@code de} port rather than being
	 * thrown away because they are not literally the id.
	 */
	public boolean matches(String answer) {
		String normalised = answer.trim().toLowerCase(Locale.ROOT);
		if (normalised.equals(id) || normalised.equals(label.toLowerCase(Locale.ROOT))) {
			return true;
		}
		if (match == null) {
			return false;
		}
		for (String alias : match.toLowerCase(Locale.ROOT).split(",")) {
			if (alias.trim().equals(normalised)) {
				return true;
			}
		}
		return false;
	}
}
