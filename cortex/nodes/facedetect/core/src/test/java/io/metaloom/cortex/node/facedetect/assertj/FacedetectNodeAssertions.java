package io.metaloom.cortex.node.facedetect.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;

/**
 * Entry point for Facedetect node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a
 * facedetect test needs — it exposes the facedetect assertions plus everything
 * inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} (generic options, validation results) and AssertJ's
 * own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.facedetect.assertj.FacedetectNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasVideoChopRate(10).hasVideoScaleSize(320);
 * </pre>
 */
public class FacedetectNodeAssertions extends NodeAssertions {

	public static FacedetectNodeOptionsAssert assertThat(FacedetectNodeOptions actual) {
		return new FacedetectNodeOptionsAssert(actual);
	}
}
