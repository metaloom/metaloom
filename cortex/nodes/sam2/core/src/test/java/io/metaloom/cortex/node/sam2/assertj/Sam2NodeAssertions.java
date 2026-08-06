package io.metaloom.cortex.node.sam2.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.sam2.Sam2NodeOptions;

/**
 * Entry point for SAM 2 node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the sam2 options assertions plus
 * everything inherited from {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * and AssertJ's own {@code Assertions}.
 * </p>
 */
public class Sam2NodeAssertions extends NodeAssertions {

	public static Sam2OptionsAssert assertThat(Sam2NodeOptions actual) {
		return new Sam2OptionsAssert(actual);
	}
}
