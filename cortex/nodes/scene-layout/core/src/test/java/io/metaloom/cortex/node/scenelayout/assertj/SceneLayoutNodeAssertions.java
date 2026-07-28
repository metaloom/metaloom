package io.metaloom.cortex.node.scenelayout.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.scenelayout.SceneLayoutNodeOptions;

/**
 * Entry point for scene-layout node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the scene-layout options
 * assertions plus everything inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class SceneLayoutNodeAssertions extends NodeAssertions {

	public static SceneLayoutOptionsAssert assertThat(SceneLayoutNodeOptions actual) {
		return new SceneLayoutOptionsAssert(actual);
	}
}
