package io.metaloom.cortex.node.imagegen.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.imagegen.ImageGenNodeOptions;

/**
 * Entry point for imagegen node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the imagegen
 * options assertions plus everything inherited from {@code NodeAssertions} (media,
 * node results), {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class ImageGenNodeAssertions extends NodeAssertions {

	public static ImageGenOptionsAssert assertThat(ImageGenNodeOptions actual) {
		return new ImageGenOptionsAssert(actual);
	}
}
