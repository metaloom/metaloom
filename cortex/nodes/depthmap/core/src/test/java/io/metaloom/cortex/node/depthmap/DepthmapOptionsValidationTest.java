package io.metaloom.cortex.node.depthmap;

import static io.metaloom.cortex.node.depthmap.assertj.DepthmapNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Validation and defaults for {@link DepthmapNodeOptions}.
 */
class DepthmapOptionsValidationTest {

	@Test
	void testDefaultsAreValid() {
		assertThat(new DepthmapNodeOptions())
			.isValid()
			.hasHost("localhost")
			// 9100 is tts, 9110 sentiment, 9200 imagegen - depth owns 9120.
			.hasPort(9120)
			.hasMode(DepthMode.RELATIVE)
			.hasMaxDim(1024);
	}

	@Test
	void testDefaultTimeoutIsSet() {
		// timeoutMs is a common option whose framework default is 0; depth inference on CPU needs
		// a real budget, so the node's constructor supplies one.
		assertThat(new DepthmapNodeOptions().getTimeoutMs()).isEqualTo(120_000L);
	}

	@Test
	void testBlankHostIsRejected() {
		assertThat(new DepthmapNodeOptions().setDepthHost("  ")).isInvalidBecauseOf("depthHost");
	}

	@Test
	void testNonPositivePortIsRejected() {
		assertThat(new DepthmapNodeOptions().setDepthPort(0)).isInvalidBecauseOf("depthPort");
	}

	@Test
	void testNullModeIsRejected() {
		assertThat(new DepthmapNodeOptions().setMode(null)).isInvalidBecauseOf("mode");
	}

	@Test
	void testNonPositiveMaxDimIsRejected() {
		assertThat(new DepthmapNodeOptions().setMaxDim(-1)).isInvalidBecauseOf("maxDim");
	}

	@Test
	void testNonPositiveTimeoutIsRejected() {
		DepthmapNodeOptions options = new DepthmapNodeOptions();
		options.setTimeoutMs(0);
		assertThat(options).isInvalidBecauseOf("timeoutMs");
	}

	@Test
	void testMetricModeIsAccepted() {
		assertThat(new DepthmapNodeOptions().setMode(DepthMode.METRIC).setModel("Intel/zoedepth-nyu-kitti"))
			.isValid()
			.hasMode(DepthMode.METRIC);
	}
}
