package io.metaloom.cortex.node.tag;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Evaluates the configured {@link TagRule} rows against the wired ports.
 *
 * <p>
 * The default strategy, and the only one that needs nothing but the pipeline definition: no model, no
 * network, and the same answer for the same inputs on any worker. That property is why it ships
 * first — a deterministic strategy proves the write path before anything non-deterministic is allowed
 * near a global tag namespace.
 * </p>
 */
public class RulesTagStrategy implements TagStrategy {

	@Inject
	public RulesTagStrategy() {
	}

	@Override
	public TagBy tagBy() {
		return TagBy.RULES;
	}

	@Override
	public Outcome compute(TagInputs inputs, TagNodeOptions options) {
		List<DesiredTag> tags = new ArrayList<>();
		List<String> skipped = new ArrayList<>();

		for (TagRule rule : options.rules()) {
			// A rule reading a port nobody wired can never fire. Saying so on the asset turns a silent
			// no-op into something an author can see, which is the difference between "the rule is
			// wrong" and "the edge is missing".
			List<String> unwired = new ArrayList<>();
			for (String port : rule.referencedPorts()) {
				if (!inputs.isWired(port)) {
					unwired.add(port);
				}
			}
			if (!unwired.isEmpty()) {
				skipped.add(rule.id() + ": input " + String.join(", ", unwired) + " not wired");
				continue;
			}

			if (rule.forEach() != null) {
				for (Object element : elements(inputs, rule.forEach())) {
					if (rule.matches(inputs, element)) {
						tags.add(new DesiredTag(rule.nameFor(element), rule.collection(), rule.id(), 1.0));
					}
				}
			} else if (rule.matches(inputs, null)) {
				tags.add(new DesiredTag(rule.nameFor(null), rule.collection(), rule.id(), 1.0));
			}
		}
		return Outcome.of(tags, skipped);
	}

	private static List<?> elements(TagInputs inputs, String portId) {
		return "labels".equals(portId) ? inputs.labels() : List.of();
	}
}
