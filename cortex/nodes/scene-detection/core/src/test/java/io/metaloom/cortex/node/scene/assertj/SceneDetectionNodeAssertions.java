package io.metaloom.cortex.node.scene.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.scene.SceneDetectionOptions;

/**
 * Entry point for Scene Detection node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a scene
 * detection test needs — it exposes the scene detection assertions plus
 * everything inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} (generic options, validation results) and AssertJ's
 * own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.scene.assertj.SceneDetectionNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class SceneDetectionNodeAssertions extends NodeAssertions {

	public static SceneDetectionOptionsAssert assertThat(SceneDetectionOptions actual) {
		return new SceneDetectionOptionsAssert(actual);
	}
}
