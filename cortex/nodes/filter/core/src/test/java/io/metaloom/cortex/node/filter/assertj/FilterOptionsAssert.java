package io.metaloom.cortex.node.filter.assertj;

import java.util.List;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.filter.FilterBucket;
import io.metaloom.cortex.node.filter.FilterBy;
import io.metaloom.cortex.node.filter.FilterNodeOptions;

/**
 * AssertJ assertions for {@link FilterNodeOptions}.
 */
public class FilterOptionsAssert extends AbstractCortexNodeOptionsAssert<FilterOptionsAssert, FilterNodeOptions> {

	public FilterOptionsAssert(FilterNodeOptions actual) {
		super(actual, FilterOptionsAssert.class);
	}

	/** Entry point, so a test can static-import {@code assertThat} for options alone. */
	public static FilterOptionsAssert assertThat(FilterNodeOptions actual) {
		return new FilterOptionsAssert(actual);
	}

	public FilterOptionsAssert hasFilterBy(FilterBy expected) {
		isNotNull();
		if (actual.getFilterBy() != expected) {
			failWithMessage("Expected filterBy to be %s but was %s", expected, actual.getFilterBy());
		}
		return this;
	}

	public FilterOptionsAssert hasModel(String expected) {
		isNotNull();
		if (!expected.equals(actual.getModel())) {
			failWithMessage("Expected model to be '%s' but was '%s'", expected, actual.getModel());
		}
		return this;
	}

	public FilterOptionsAssert hasMinConfidence(double expected) {
		isNotNull();
		if (actual.getMinConfidence() != expected) {
			failWithMessage("Expected minConfidence to be %s but was %s", expected, actual.getMinConfidence());
		}
		return this;
	}

	/**
	 * The bucket ids that survived parsing — which is what becomes the node's branch ports.
	 */
	public FilterOptionsAssert hasBucketIds(String... expected) {
		isNotNull();
		List<String> ids = actual.buckets().stream().map(FilterBucket::id).toList();
		if (!ids.equals(List.of(expected))) {
			failWithMessage("Expected bucket ids %s but was %s", List.of(expected), ids);
		}
		return this;
	}
}
