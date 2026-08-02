package io.metaloom.cortex.node.filter.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.filter.FilterNodeOptions;

/**
 * Entry point for filter node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the filter options assertions
 * plus everything inherited from {@code NodeAssertions} (media, node results), {@code
 * OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class FilterNodeAssertions extends NodeAssertions {

	public static FilterOptionsAssert assertThat(FilterNodeOptions actual) {
		return new FilterOptionsAssert(actual);
	}
}
