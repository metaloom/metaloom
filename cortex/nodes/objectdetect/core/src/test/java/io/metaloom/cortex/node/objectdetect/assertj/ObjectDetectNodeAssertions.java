package io.metaloom.cortex.node.objectdetect.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNodeOptions;

/**
 * Entry point for objectdetect node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import is all an objectdetect test needs — it
 * exposes the assertions below plus everything inherited from {@code NodeAssertions} (media, node
 * results), {@code OptionsAssertions} (generic options, validation results) and AssertJ's own
 * {@code Assertions}:
 * </p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.objectdetect.assertj.ObjectDetectNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasVideoChopRate(10).hasMinConfidence(0.6f);
 * </pre>
 */
public class ObjectDetectNodeAssertions extends NodeAssertions {

	public static ObjectDetectNodeOptionsAssert assertThat(ObjectDetectNodeOptions actual) {
		return new ObjectDetectNodeOptionsAssert(actual);
	}
}
