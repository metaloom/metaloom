package io.metaloom.cortex.node.watermark;

import static io.metaloom.cortex.node.watermark.assertj.WatermarkNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Validation test for {@link WatermarkNodeOptions}.
 *
 * <p>
 * {@code RegistryNodeRegistrar} runs {@code validate()} when it builds the node, so everything asserted here is reported when the pipeline starts rather
 * than once per item.
 * </p>
 */
class WatermarkOptionsValidationTest {

	private WatermarkNodeOptions valid() throws Exception {
		return new WatermarkNodeOptions().setWatermarkBase64(WatermarkFixtures.markBase64(8, 8));
	}

	@Test
	void testDefaultsAreValidOnceAWatermarkIsSet() throws Exception {
		assertThat(valid())
			.isValid()
			.hasRelX(0.95)
			.hasRelY(0.95)
			.hasScale(0.20)
			.hasOpacity(1.0)
			.hasVideoCodec("libx264")
			.hasVideoCrf(23);
	}

	@Test
	void testMissingWatermarkIsRejected() {
		// The bare defaults are deliberately invalid: a watermark node with no watermark can only be a misconfiguration.
		assertThat(new WatermarkNodeOptions())
			.isInvalid()
			.hasError("watermarkBase64 must not be empty");
	}

	@Test
	void testUndecodableWatermarkIsRejectedUpFront() {
		assertThat(new WatermarkNodeOptions().setWatermarkBase64("!!! not base64 !!!"))
			.isInvalid()
			.hasErrorMatching(error -> error.startsWith("watermarkBase64 is not valid base64"));
	}

	@Test
	void testOutOfRangePlacementIsRejected() throws Exception {
		assertThat(valid().setRelX(1.5)).isInvalid().hasError("relX must be within [0,1], got 1.5");
		assertThat(valid().setRelY(-0.5)).isInvalid().hasError("relY must be within [0,1], got -0.5");
		assertThat(valid().setScale(2.0)).isInvalid().hasError("scale must be within [0,1], got 2.0");
	}

	@Test
	void testZeroOpacityIsRejectedButZeroScaleIsNot() throws Exception {
		// scale 0 is meaningful - "use the overlay's native size" - while opacity 0 would render nothing at all, which is never what an author wants.
		assertThat(valid().setOpacity(0.0)).isInvalid().hasError("opacity must be within (0,1], got 0.0");
		assertThat(valid().setScale(0.0)).isValid();
	}

	@Test
	void testCrfOutsideTheEncoderRangeIsRejected() throws Exception {
		assertThat(valid().setVideoCrf(52)).isInvalid().hasError("videoCrf must be within [0,51], got 52");
		assertThat(valid().setVideoCrf(-1)).isInvalid().hasError("videoCrf must be within [0,51], got -1");
		assertThat(valid().setVideoCrf(0)).isValid();
	}

	@Test
	void testBlankExecutablePathsAreRejected() throws Exception {
		assertThat(valid().setFfmpegPath(" ")).isInvalid().hasError("ffmpegPath must not be empty");
		assertThat(valid().setFfprobePath("")).isInvalid().hasError("ffprobePath must not be empty");
		assertThat(valid().setVideoPreset("")).isInvalid().hasError("videoPreset must not be empty");
		assertThat(valid().setVideoCodec("")).isInvalid().hasError("videoCodec must not be empty");
	}

	@Test
	void testEveryProblemIsReportedTogether() throws Exception {
		// One start-up failure listing everything wrong beats three restarts.
		assertThat(valid().setRelX(9.0).setOpacity(0.0).setVideoCrf(99))
			.isInvalid()
			.hasErrorCount(3);
	}
}
