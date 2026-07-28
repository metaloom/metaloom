package io.metaloom.cortex.node.color.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.color.DominantColorNodeOptions;

/**
 * Entry point for dominant-colour node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the dominant-colour options
 * assertions plus everything inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class DominantColorNodeAssertions extends NodeAssertions {

	public static DominantColorOptionsAssert assertThat(DominantColorNodeOptions actual) {
		return new DominantColorOptionsAssert(actual);
	}
}
