package io.metaloom.cortex.node.guard.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.guard.GuardNodeOptions;

/**
 * Entry point for guard node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the guard options assertions
 * plus everything inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class GuardNodeAssertions extends NodeAssertions {

	public static GuardOptionsAssert assertThat(GuardNodeOptions actual) {
		return new GuardOptionsAssert(actual);
	}
}
