package io.metaloom.cortex.node.scenelayout;

import static io.metaloom.cortex.node.scenelayout.assertj.SceneLayoutNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Validation and defaults for {@link SceneLayoutNodeOptions}.
 */
class SceneLayoutOptionsValidationTest {

	@Test
	void testDefaultsAreValid() {
		assertThat(new SceneLayoutNodeOptions())
			.isValid()
			.hasCoreInset(0.25)
			.hasDepthZThreshold(1.0);
	}

	@Test
	void testCoreInsetMustLeaveACore() {
		// An inset of 0.5 removes the whole box from both sides - there would be nothing to sample.
		assertThat(new SceneLayoutNodeOptions().setCoreInset(0.5)).isInvalidBecauseOf("coreInset");
		assertThat(new SceneLayoutNodeOptions().setCoreInset(-0.1)).isInvalidBecauseOf("coreInset");
		assertThat(new SceneLayoutNodeOptions().setCoreInset(0.49)).isValid();
	}

	@Test
	void testNonPositiveDepthThresholdIsRejected() {
		// A threshold of zero would assert an ordering from any difference at all, including noise.
		assertThat(new SceneLayoutNodeOptions().setDepthZThreshold(0)).isInvalidBecauseOf("depthZThreshold");
	}

	@Test
	void testRatiosMustBeFractions() {
		assertThat(new SceneLayoutNodeOptions().setOcclusionMinOverlap(1.5)).isInvalidBecauseOf("occlusionMinOverlap");
		assertThat(new SceneLayoutNodeOptions().setContainmentRatio(0)).isInvalidBecauseOf("containmentRatio");
	}

	@Test
	void testBackgroundQuantileMustSitBelowForeground() {
		// Inverted bands would classify every object as both, so the pair is validated together
		// rather than only individually.
		assertThat(new SceneLayoutNodeOptions().setBackgroundQuantile(0.8).setForegroundQuantile(0.4))
			.isInvalidBecauseOf("must be below foregroundQuantile");

		assertThat(new SceneLayoutNodeOptions().setBackgroundQuantile(0.2).setForegroundQuantile(0.8)).isValid();
	}

	@Test
	void testEqualQuantilesAreRejected() {
		assertThat(new SceneLayoutNodeOptions().setBackgroundQuantile(0.5).setForegroundQuantile(0.5))
			.isInvalidBecauseOf("must be below foregroundQuantile");
	}

	@Test
	void testNonPositiveCapsAreRejected() {
		assertThat(new SceneLayoutNodeOptions().setMaxObjects(0)).isInvalidBecauseOf("maxObjects");
		assertThat(new SceneLayoutNodeOptions().setMaxRelations(0)).isInvalidBecauseOf("maxRelations");
		assertThat(new SceneLayoutNodeOptions().setMinCorePixels(0)).isInvalidBecauseOf("minCorePixels");
	}
}
