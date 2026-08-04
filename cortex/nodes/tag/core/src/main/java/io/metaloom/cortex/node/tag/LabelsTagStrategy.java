package io.metaloom.cortex.node.tag;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Turns every element on the {@code labels} port into a tag.
 *
 * <p>
 * The terminal for the nodes that already emit tag-shaped strings and have nowhere to put them:
 * {@code dominant-color}'s colour names, {@code sentiment}'s polarity label, a {@code filter}
 * bucket, a declared {@code script} output. Their results live in {@code asset_json_comp} today,
 * where search cannot see them; one edge into this node makes them GIN-indexed facets.
 * </p>
 *
 * <p>
 * No rules, so nothing gates the vocabulary except the node's own {@code allowedTags} and
 * {@code maxTags} — which is precisely why those exist. A label list from a model is unbounded, and a
 * tag row outlives the run that created it.
 * </p>
 */
public class LabelsTagStrategy implements TagStrategy {

	@Inject
	public LabelsTagStrategy() {
	}

	@Override
	public TagBy tagBy() {
		return TagBy.LABELS;
	}

	@Override
	public Outcome compute(TagInputs inputs, TagNodeOptions options) {
		List<DesiredTag> tags = new ArrayList<>();
		List<String> skipped = new ArrayList<>();
		if (inputs.labels().isEmpty()) {
			skipped.add("labels: input labels not wired");
			return Outcome.of(tags, skipped);
		}
		for (String label : inputs.labels()) {
			tags.add(new DesiredTag(label, null, "labels", 1.0));
		}
		return Outcome.of(tags, skipped);
	}
}
