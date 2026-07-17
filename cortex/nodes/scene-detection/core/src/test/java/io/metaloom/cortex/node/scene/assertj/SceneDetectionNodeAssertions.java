package io.metaloom.cortex.node.scene.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.scene.SceneDetectionOptions;

/**
 * Entry point for Scene Detection node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.scene.assertj.SceneDetectionNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class SceneDetectionNodeAssertions extends Assertions {

	public static SceneDetectionOptionsAssert assertThat(SceneDetectionOptions actual) {
		return new SceneDetectionOptionsAssert(actual);
	}
}