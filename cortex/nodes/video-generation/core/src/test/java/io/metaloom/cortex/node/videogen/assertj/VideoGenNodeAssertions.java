package io.metaloom.cortex.node.videogen.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.videogen.VideoGenNodeOptions;

/**
 * Entry point for videogen node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the videogen
 * options assertions plus everything inherited from {@code NodeAssertions} (media,
 * node results), {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class VideoGenNodeAssertions extends NodeAssertions {

	public static VideoGenOptionsAssert assertThat(VideoGenNodeOptions actual) {
		return new VideoGenOptionsAssert(actual);
	}
}
