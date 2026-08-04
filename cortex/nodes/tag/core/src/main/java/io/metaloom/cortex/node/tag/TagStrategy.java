package io.metaloom.cortex.node.tag;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides which tags an item should carry.
 *
 * <p>
 * One implementation per {@link TagBy} value, bound into a map so {@link TagNode} never needs to know
 * which ones exist. A strategy answers with names only: normalisation, the allow-list, the cap, the
 * diff against the previous run and every write to Loom belong to the node, so a future {@code LLM}
 * or {@code VLM} strategy inherits all of that safety for free.
 * </p>
 */
public interface TagStrategy {

	/** The {@code tagBy} value this strategy serves. */
	TagBy tagBy();

	/**
	 * Decide the tags for one item.
	 *
	 * @param inputs  what the wired ports carry for this item
	 * @param options the node's per-instance configuration
	 * @return the desired tags and any diagnostics; never null
	 * @throws Exception when the strategy could not do its job at all (an unreachable model). Deciding
	 *                   that an item gets no tags is a result, not a failure — return an empty outcome
	 */
	Outcome compute(TagInputs inputs, TagNodeOptions options) throws Exception;

	/**
	 * A tag a strategy wants attached.
	 *
	 * @param name       the tag name, before normalisation
	 * @param collection the collection, or null to fall back to the node's option
	 * @param ruleId     what produced it, recorded so a later run can attribute it
	 * @param confidence 0..1; {@code 1} for the deterministic strategies
	 */
	record DesiredTag(String name, String collection, String ruleId, double confidence) {
	}

	/**
	 * What a strategy decided, plus what it could not do.
	 *
	 * @param tags         the desired tags, in rule order
	 * @param skippedRules human-readable notes about rules that could not be evaluated — an unwired
	 *                     port is the common case. Recorded on the asset so a rule that never fires is
	 *                     visible rather than merely absent
	 */
	record Outcome(List<DesiredTag> tags, List<String> skippedRules) {

		public static Outcome of(List<DesiredTag> tags, List<String> skippedRules) {
			return new Outcome(tags == null ? List.of() : tags, skippedRules == null ? List.of() : skippedRules);
		}

		public static Outcome empty() {
			return new Outcome(new ArrayList<>(), new ArrayList<>());
		}
	}
}
