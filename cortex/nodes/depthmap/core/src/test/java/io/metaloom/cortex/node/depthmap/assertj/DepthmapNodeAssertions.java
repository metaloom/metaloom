package io.metaloom.cortex.node.depthmap.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.depthmap.DepthmapNodeOptions;

/**
 * Entry point for depthmap node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the depthmap options
 * assertions plus everything inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class DepthmapNodeAssertions extends NodeAssertions {

	public static DepthmapOptionsAssert assertThat(DepthmapNodeOptions actual) {
		return new DepthmapOptionsAssert(actual);
	}
}
