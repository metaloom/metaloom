package io.metaloom.cortex.node.facedetect.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;

/**
 * Entry point for Facedetect node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.facedetect.assertj.FacedetectNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasVideoChopRate(10).hasVideoScaleSize(320);
 * </pre>
 */
public class FacedetectNodeAssertions extends Assertions {

	public static FacedetectNodeOptionsAssert assertThat(FacedetectNodeOptions actual) {
		return new FacedetectNodeOptionsAssert(actual);
	}
}