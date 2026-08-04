package io.metaloom.cortex.node.tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.metaloom.cortex.api.node.spec.ParamDoc;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;
import io.metaloom.loom.nodes.spec.ParameterType;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Per-instance configuration for a {@link TagNode}.
 *
 * <p>
 * These arrive from the <em>pipeline definition</em>, not the worker's YAML — the node is a
 * {@code PipelineConfigurable}, so two tag nodes in one graph tag by different rules and any worker
 * can serve any rule set. Nothing model-shaped is worker-scoped, which is what keeps this node
 * runnable everywhere including the demo container.
 * </p>
 */
public class TagNodeOptions extends AbstractNodeOptions<TagNodeOptions> {

	public static final String KEY = "tag";

	/** How a name is cleaned up before it becomes a permanent, globally shared row. */
	public enum Normalize {
		NONE,
		TRIM,
		TRIM_LOWER
	}

	@ParamDoc(label = "Tag By", description = "How the tags for an item are decided")
	private TagBy tagBy = TagBy.RULES;

	/**
	 * The rule rows.
	 *
	 * <p>
	 * Declared {@code JSON} rather than {@code PORT_LIST}: a row's id does not become a port here, and
	 * a rule carries a nested {@code when} array that the flat three-column row editor behind
	 * {@code PORT_LIST} cannot express. A dedicated rule editor is worth building — this parameter is
	 * the reason to build it — but declaring the wrong widget to get a nicer form today would make the
	 * contract lie about what the value is.
	 * </p>
	 */
	@ParamDoc(label = "Rules", type = ParameterType.JSON,
		description = "Rows of {id, tag|tagTemplate, collection, match, forEach, when[]}. A row attaches its tag when its conditions hold")
	private JsonArray rules = new JsonArray();

	@ParamDoc(label = "Collection",
		description = "Collection the tags are written to. Keeping machine tags in their own collection is what makes them "
			+ "distinguishable from tags a person typed")
	private String collection = "auto";

	@ParamDoc(label = "Allowed Tags", type = ParameterType.JSON,
		description = "Controlled vocabulary. When non-empty, a computed name outside it is dropped and recorded as rejected")
	private JsonArray allowedTags = new JsonArray();

	@ParamDoc(label = "Max Tags", description = "Hard cap on the tags attached to one item", min = "1")
	private int maxTags = 20;

	@ParamDoc(label = "Normalize", description = "How a computed name is cleaned up before it is written")
	private Normalize normalize = Normalize.TRIM_LOWER;

	@ParamDoc(label = "Remove Withdrawn",
		description = "Withdraw tags this node previously applied and no longer stands behind. Off by default: deleting is not a default")
	private boolean removeWithdrawn;

	@ParamDoc(label = "Dry Run", description = "Compute and record the verdict but attach no tags. The way to try a rule set against a real library")
	private boolean dryRun;

	@ParamDoc(label = "Minimum Confidence", description = "Tags below this confidence are dropped. Ignored by the deterministic strategies",
		min = "0.0", max = "1.0")
	private double minConfidence;

	public TagBy getTagBy() {
		return tagBy;
	}

	public TagNodeOptions setTagBy(TagBy tagBy) {
		this.tagBy = tagBy;
		return this;
	}

	public JsonArray getRules() {
		return rules;
	}

	public TagNodeOptions setRules(JsonArray rules) {
		this.rules = rules;
		return this;
	}

	public String getCollection() {
		return collection;
	}

	public TagNodeOptions setCollection(String collection) {
		this.collection = collection;
		return this;
	}

	public JsonArray getAllowedTags() {
		return allowedTags;
	}

	public TagNodeOptions setAllowedTags(JsonArray allowedTags) {
		this.allowedTags = allowedTags;
		return this;
	}

	public int getMaxTags() {
		return maxTags;
	}

	public TagNodeOptions setMaxTags(int maxTags) {
		this.maxTags = maxTags;
		return this;
	}

	public Normalize getNormalize() {
		return normalize;
	}

	public TagNodeOptions setNormalize(Normalize normalize) {
		this.normalize = normalize;
		return this;
	}

	public boolean isRemoveWithdrawn() {
		return removeWithdrawn;
	}

	public TagNodeOptions setRemoveWithdrawn(boolean removeWithdrawn) {
		this.removeWithdrawn = removeWithdrawn;
		return this;
	}

	public boolean isDryRun() {
		return dryRun;
	}

	public TagNodeOptions setDryRun(boolean dryRun) {
		this.dryRun = dryRun;
		return this;
	}

	public double getMinConfidence() {
		return minConfidence;
	}

	public TagNodeOptions setMinConfidence(double minConfidence) {
		this.minConfidence = minConfidence;
		return this;
	}

	/**
	 * The configured rules as value objects, malformed rows dropped.
	 *
	 * <p>
	 * Dropping rather than throwing here matches the filter node: at evaluation time the author has
	 * already saved, {@link #validate()} has already reported what is wrong, and refusing to run the
	 * nineteen good rows because the twentieth is half-typed helps nobody.
	 * </p>
	 */
	public List<TagRule> rules() {
		List<TagRule> parsed = new ArrayList<>();
		if (rules == null) {
			return parsed;
		}
		Set<String> seen = new LinkedHashSet<>();
		for (Object entry : rules) {
			if (!(entry instanceof JsonObject json)) {
				continue;
			}
			TagRule rule = TagRule.from(json);
			if (rule.validate().isEmpty() && seen.add(rule.id())) {
				parsed.add(rule);
			}
		}
		return parsed;
	}

	/** The allow-list, normalised the same way computed names are so the comparison is meaningful. */
	public Set<String> allowedTags() {
		Set<String> allowed = new LinkedHashSet<>();
		if (allowedTags == null) {
			return allowed;
		}
		for (Object entry : allowedTags) {
			if (entry != null) {
				String name = normalize(String.valueOf(entry));
				if (name != null) {
					allowed.add(name);
				}
			}
		}
		return allowed;
	}

	/**
	 * Clean up a computed name, or return null when nothing usable is left.
	 *
	 * <p>
	 * This runs before the allow-list and before the write, because {@code "blurry "} and
	 * {@code "blurry"} would otherwise be two permanent rows in a namespace shared with every person
	 * using the instance.
	 * </p>
	 */
	public String normalize(String name) {
		if (name == null) {
			return null;
		}
		String result = switch (normalize) {
		case NONE -> name;
		case TRIM -> name.trim();
		case TRIM_LOWER -> name.trim().toLowerCase(Locale.ROOT);
		};
		return result.isBlank() ? null : result;
	}

	@Override
	protected TagNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		List<String> errors = new ArrayList<>(validateCommon());

		if (tagBy == null) {
			errors.add("tagBy must be set");
		}
		if (normalize == null) {
			errors.add("normalize must be set");
		}
		if (collection == null || collection.isBlank()) {
			errors.add("collection must not be empty");
		}
		if (maxTags <= 0) {
			errors.add("maxTags must be greater than 0");
		}
		if (minConfidence < 0 || minConfidence > 1) {
			errors.add("minConfidence must be between 0 and 1");
		}
		if (tagBy == TagBy.RULES && (rules == null || rules.isEmpty())) {
			errors.add("at least one rule is required when tagBy is RULES");
		}

		// Rules are reported rather than dropped here: ignoring a row someone typed is right while they
		// are still editing and wrong once they have saved.
		Set<String> seen = new LinkedHashSet<>();
		if (rules != null) {
			for (Object entry : rules) {
				if (!(entry instanceof JsonObject json)) {
					errors.add("every rule must be an object");
					continue;
				}
				TagRule rule = TagRule.from(json);
				errors.addAll(rule.validate());
				if (rule.id() != null && !seen.add(rule.id())) {
					errors.add("duplicate rule id '" + rule.id() + "'");
				}
			}
		}

		return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
	}
}
