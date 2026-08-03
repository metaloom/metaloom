package io.metaloom.cortex.node.imagemanip.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.imagemanip.ImageManipulationNodeOptions;

/**
 * Entry point for image manipulation node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the options assertions plus everything inherited from {@code NodeAssertions} (media,
 * node results), {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class ImageManipulationNodeAssertions extends NodeAssertions {

	public static ImageManipulationOptionsAssert assertThat(ImageManipulationNodeOptions actual) {
		return new ImageManipulationOptionsAssert(actual);
	}
}
