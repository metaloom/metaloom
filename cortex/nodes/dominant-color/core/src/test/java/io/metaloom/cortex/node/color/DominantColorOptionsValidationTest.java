package io.metaloom.cortex.node.color;

import static io.metaloom.cortex.node.color.assertj.DominantColorNodeAssertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

public class DominantColorOptionsValidationTest {

	@Test
	public void testDefaultsAreValid() {
		DominantColorNodeOptions options = new DominantColorNodeOptions();

		assertThat(options)
			.isValid()
			.hasClusterCount(5)
			.hasMaxSamples(40_000)
			.hasSeed(42L)
			.hasAlphaThreshold(128)
			.hasAchromaticChroma(12.0d)
			.hasDetectionSources("facedetect");

		assertThat(options.isEnabled()).isTrue();
		assertThat(options.getMaxIterations()).isEqualTo(30);
		assertThat(options.getMinRegionPixels()).isEqualTo(64);
		assertThat(options.getMaxRegions()).isEqualTo(32);
		assertThat(options.isIncludeWholeImage()).isTrue();
		assertThat(options.isUseDetections()).isTrue();
		assertThat(options.isEmitPalette()).isTrue();
		assertThat(options.getRegionCoordinates()).isEqualTo(DominantColorNodeOptions.NORMALIZED);
		assertThat(options.hasStaticRegion()).isFalse();
	}

	@Test
	public void testClusterCountIsBounded() {
		assertInvalid(new DominantColorNodeOptions().setClusterCount(0), "clusterCount");
		assertInvalid(new DominantColorNodeOptions().setClusterCount(17), "clusterCount");
		assertThat(new DominantColorNodeOptions().setClusterCount(16).validate().isValid()).isTrue();
	}

	@Test
	public void testSampleAndIterationBudgetsAreBounded() {
		assertInvalid(new DominantColorNodeOptions().setMaxSamples(255), "maxSamples");
		assertInvalid(new DominantColorNodeOptions().setMaxSamples(1_000_001), "maxSamples");
		assertInvalid(new DominantColorNodeOptions().setMaxIterations(0), "maxIterations");
		assertInvalid(new DominantColorNodeOptions().setMaxIterations(501), "maxIterations");
		assertInvalid(new DominantColorNodeOptions().setConvergenceEpsilon(0d), "convergenceEpsilon");
	}

	@Test
	public void testAlphaThresholdIsAByteRange() {
		assertInvalid(new DominantColorNodeOptions().setAlphaThreshold(-1), "alphaThreshold");
		assertInvalid(new DominantColorNodeOptions().setAlphaThreshold(256), "alphaThreshold");
		assertThat(new DominantColorNodeOptions().setAlphaThreshold(0).validate().isValid()).isTrue();
		assertThat(new DominantColorNodeOptions().setAlphaThreshold(255).validate().isValid()).isTrue();
	}

	@Test
	public void testDetectionSourcesMustBeUsableWhenDetectionsAreEnabled() {
		assertInvalid(new DominantColorNodeOptions().setDetectionSources(List.of()), "detectionSources");
		assertInvalid(new DominantColorNodeOptions().setDetectionSources(Arrays.asList("facedetect", " ")), "detectionSources");

		// With detections switched off the list is irrelevant.
		assertThat(new DominantColorNodeOptions().setUseDetections(false).setDetectionSources(List.of()).validate().isValid())
			.isTrue();
	}

	@Test
	public void testAHalfSpecifiedStaticRegionIsRejected() {
		assertInvalid(new DominantColorNodeOptions().setRegionW(0.5d), "region");
		assertInvalid(new DominantColorNodeOptions().setRegionH(0.5d), "region");
		assertInvalid(new DominantColorNodeOptions().setRegionX(0.5d), "region");
	}

	@Test
	public void testANormalizedRegionMustFitInsideTheUnitSquare() {
		assertInvalid(new DominantColorNodeOptions()
			.setRegionX(0.6d).setRegionY(0d).setRegionW(0.5d).setRegionH(0.5d), "unit square");

		assertThat(new DominantColorNodeOptions()
			.setRegionX(0.5d).setRegionY(0.5d).setRegionW(0.5d).setRegionH(0.5d).validate().isValid()).isTrue();

		// An absolute region is measured in pixels, so the unit-square rule must not apply to it.
		assertThat(new DominantColorNodeOptions()
			.setRegionCoordinates(DominantColorNodeOptions.ABSOLUTE_PIXELS)
			.setRegionX(100).setRegionY(50).setRegionW(200).setRegionH(100).validate().isValid()).isTrue();
	}

	@Test
	public void testUnknownRegionCoordinatesAreRejected() {
		assertInvalid(new DominantColorNodeOptions().setRegionCoordinates("PERCENT"), "regionCoordinates");
	}

	/**
	 * A node configured to measure nothing would skip every asset, which reads as a broken
	 * pipeline rather than as a broken configuration.
	 */
	@Test
	public void testAtLeastOneRegionSourceMustBeEnabled() {
		assertInvalid(new DominantColorNodeOptions().setIncludeWholeImage(false).setUseDetections(false),
			"at least one region source");

		assertThat(new DominantColorNodeOptions()
			.setIncludeWholeImage(false).setUseDetections(false)
			.setRegionW(0.5d).setRegionH(0.5d).validate().isValid()).isTrue();
	}

	@Test
	public void testNamingThresholdsAreBoundedAndOrdered() {
		assertInvalid(new DominantColorNodeOptions().setAchromaticChroma(-1d), "achromaticChroma");
		assertInvalid(new DominantColorNodeOptions().setAchromaticChroma(61d), "achromaticChroma");
		assertInvalid(new DominantColorNodeOptions().setBlackLightness(-1d), "blackLightness");
		assertInvalid(new DominantColorNodeOptions().setWhiteLightness(101d), "whiteLightness");
		assertInvalid(new DominantColorNodeOptions().setBlackLightness(90d), "must be below whiteLightness");
	}

	@Test
	public void testRegionAndPixelCapsMustBePositive() {
		assertInvalid(new DominantColorNodeOptions().setMinRegionPixels(0), "minRegionPixels");
		assertInvalid(new DominantColorNodeOptions().setMaxRegions(0), "maxRegions");
	}

	private static void assertInvalid(DominantColorNodeOptions options, String expectedFragment) {
		assertThat(options).isInvalidBecauseOf(expectedFragment);
	}
}
