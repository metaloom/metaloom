package io.metaloom.cortex.node.tag.assertj;

import java.util.List;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.tag.TagBy;
import io.metaloom.cortex.node.tag.TagNodeOptions;
import io.metaloom.cortex.node.tag.TagRule;

/**
 * AssertJ assertions for {@link TagNodeOptions}.
 */
public class TagOptionsAssert extends AbstractCortexNodeOptionsAssert<TagOptionsAssert, TagNodeOptions> {

	public TagOptionsAssert(TagNodeOptions actual) {
		super(actual, TagOptionsAssert.class);
	}

	/** Entry point, so a test can static-import {@code assertThat} for options alone. */
	public static TagOptionsAssert assertThat(TagNodeOptions actual) {
		return new TagOptionsAssert(actual);
	}

	public TagOptionsAssert hasTagBy(TagBy expected) {
		isNotNull();
		if (actual.getTagBy() != expected) {
			failWithMessage("Expected tagBy to be %s but was %s", expected, actual.getTagBy());
		}
		return this;
	}

	public TagOptionsAssert hasCollection(String expected) {
		isNotNull();
		if (!expected.equals(actual.getCollection())) {
			failWithMessage("Expected collection to be '%s' but was '%s'", expected, actual.getCollection());
		}
		return this;
	}

	public TagOptionsAssert hasMaxTags(int expected) {
		isNotNull();
		if (actual.getMaxTags() != expected) {
			failWithMessage("Expected maxTags to be %s but was %s", expected, actual.getMaxTags());
		}
		return this;
	}

	/** The rule ids that survived parsing - which is what the node will actually evaluate. */
	public TagOptionsAssert hasRuleIds(String... expected) {
		isNotNull();
		List<String> ids = actual.rules().stream().map(TagRule::id).toList();
		if (!ids.equals(List.of(expected))) {
			failWithMessage("Expected rule ids %s but was %s", List.of(expected), ids);
		}
		return this;
	}

	/** How a name is cleaned up before it becomes a permanent, globally shared row. */
	public TagOptionsAssert normalizesTo(String raw, String expected) {
		isNotNull();
		String actualName = actual.normalize(raw);
		if (expected == null ? actualName != null : !expected.equals(actualName)) {
			failWithMessage("Expected '%s' to normalize to '%s' but was '%s'", raw, expected, actualName);
		}
		return this;
	}
}
