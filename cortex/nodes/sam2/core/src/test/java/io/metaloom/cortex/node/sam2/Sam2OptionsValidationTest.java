package io.metaloom.cortex.node.sam2;

import static io.metaloom.cortex.node.sam2.assertj.Sam2NodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Validation and defaults for {@link Sam2NodeOptions}.
 */
class Sam2OptionsValidationTest {

	@Test
	void testDefaultsAreValid() {
		assertThat(new Sam2NodeOptions())
			.isValid()
			.hasHost("localhost")
			// 9100 is tts, 9110 sentiment, 9120 depth - sam2 owns 9130.
			.hasPort(9130)
			.hasMode(Sam2Mode.AUTOMATIC)
			.hasMaxDim(1024)
			.hasPointsPerSide(32)
			.hasMaxMasks(64)
			.hasMaxFrames(64)
			.hasVideoChopRate(25)
			// On by default because it is the only preview that shows the whole result: NodePreviews
			// downsamples just the first element of a MANY port.
			.emitsOverlay(true);
	}

	@Test
	void testDefaultTimeoutIsSet() {
		// timeoutMs is a common option whose framework default is 0. Segment-everything at
		// pointsPerSide=32 is 1024 forward passes, so the node's constructor supplies a real budget.
		assertThat(new Sam2NodeOptions().getTimeoutMs()).isEqualTo(300_000L);
	}

	@Test
	void testBlankHostIsRejected() {
		assertThat(new Sam2NodeOptions().setSam2Host("  ")).isInvalidBecauseOf("sam2Host");
	}

	@Test
	void testNonPositivePortIsRejected() {
		assertThat(new Sam2NodeOptions().setSam2Port(0)).isInvalidBecauseOf("sam2Port");
	}

	@Test
	void testNullModeIsRejected() {
		assertThat(new Sam2NodeOptions().setMode(null)).isInvalidBecauseOf("mode");
	}

	@Test
	void testNonPositiveMaxDimIsRejected() {
		assertThat(new Sam2NodeOptions().setMaxDim(0)).isInvalidBecauseOf("maxDim");
	}

	@Test
	void testNonPositivePointsPerSideIsRejected() {
		assertThat(new Sam2NodeOptions().setPointsPerSide(0)).isInvalidBecauseOf("pointsPerSide");
	}

	@Test
	void testPredIouThreshOutsideUnitRangeIsRejected() {
		assertThat(new Sam2NodeOptions().setPredIouThresh(-0.1d)).isInvalidBecauseOf("predIouThresh");
		assertThat(new Sam2NodeOptions().setPredIouThresh(1.1d)).isInvalidBecauseOf("predIouThresh");
	}

	@Test
	void testStabilityScoreThreshOutsideUnitRangeIsRejected() {
		assertThat(new Sam2NodeOptions().setStabilityScoreThresh(-0.1d)).isInvalidBecauseOf("stabilityScoreThresh");
		assertThat(new Sam2NodeOptions().setStabilityScoreThresh(1.1d)).isInvalidBecauseOf("stabilityScoreThresh");
	}

	@Test
	void testNegativeMinMaskAreaIsRejected() {
		assertThat(new Sam2NodeOptions().setMinMaskArea(-1)).isInvalidBecauseOf("minMaskArea");
	}

	@Test
	void testZeroMinMaskAreaIsAllowed() {
		// Zero means "keep every mask", which is a legitimate configuration - unlike a negative area,
		// which is not a threshold at all.
		assertThat(new Sam2NodeOptions().setMinMaskArea(0)).isValid();
	}

	@Test
	void testNonPositiveMaxMasksIsRejected() {
		assertThat(new Sam2NodeOptions().setMaxMasks(0)).isInvalidBecauseOf("maxMasks");
	}

	@Test
	void testNonPositiveVideoChopRateIsRejected() {
		assertThat(new Sam2NodeOptions().setVideoChopRate(0)).isInvalidBecauseOf("videoChopRate");
	}

	@Test
	void testNonPositiveMaxFramesIsRejected() {
		assertThat(new Sam2NodeOptions().setMaxFrames(0)).isInvalidBecauseOf("maxFrames");
	}

	@Test
	void testNegativeTrackFrameIsRejected() {
		assertThat(new Sam2NodeOptions().setTrackFrame(-1)).isInvalidBecauseOf("trackFrame");
	}

	@Test
	void testNonPositiveTimeoutIsRejected() {
		Sam2NodeOptions options = new Sam2NodeOptions();
		options.setTimeoutMs(0);
		assertThat(options).isInvalidBecauseOf("timeoutMs");
	}

	@Test
	void testMultimaskWithATightMaxMasksIsStillValid() {
		// The two interact - three candidates per box are produced before the cap applies - but both
		// settings are individually legal, so this is documented on the parameter rather than rejected.
		assertThat(new Sam2NodeOptions().setMultimask(true).setMaxMasks(1)).isValid();
	}
}
