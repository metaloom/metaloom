package io.metaloom.cortex.node.quality.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.quality.QualityNodeOptions;

/**
 * AssertJ assertions for {@link QualityNodeOptions}.
 */
public class QualityNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<QualityNodeOptionsAssert, QualityNodeOptions> {

	public QualityNodeOptionsAssert(QualityNodeOptions actual) {
		super(actual, QualityNodeOptionsAssert.class);
	}

	/**
	 * Assert that blurriness check is enabled/disabled.
	 */
	public QualityNodeOptionsAssert hasCheckBlurriness(boolean expected) {
		isNotNull();
		if (actual.isCheckBlurriness() != expected) {
			failWithMessage("Expected checkBlurriness to be %s but was %s", expected, actual.isCheckBlurriness());
		}
		return this;
	}

	/**
	 * Assert that resolution check is enabled/disabled.
	 */
	public QualityNodeOptionsAssert hasCheckResolution(boolean expected) {
		isNotNull();
		if (actual.isCheckResolution() != expected) {
			failWithMessage("Expected checkResolution to be %s but was %s", expected, actual.isCheckResolution());
		}
		return this;
	}

	/**
	 * Assert that video bitrate check is enabled/disabled.
	 */
	public QualityNodeOptionsAssert hasCheckVideoBitrate(boolean expected) {
		isNotNull();
		if (actual.isCheckVideoBitrate() != expected) {
			failWithMessage("Expected checkVideoBitrate to be %s but was %s", expected, actual.isCheckVideoBitrate());
		}
		return this;
	}

	/**
	 * Assert that audio bitrate check is enabled/disabled.
	 */
	public QualityNodeOptionsAssert hasCheckAudioBitrate(boolean expected) {
		isNotNull();
		if (actual.isCheckAudioBitrate() != expected) {
			failWithMessage("Expected checkAudioBitrate to be %s but was %s", expected, actual.isCheckAudioBitrate());
		}
		return this;
	}

	/**
	 * Assert that at least one quality check is enabled.
	 */
	public QualityNodeOptionsAssert hasAtLeastOneCheckEnabled() {
		isNotNull();
		if (!actual.isCheckBlurriness() && !actual.isCheckResolution() && !actual.isCheckVideoBitrate() && !actual.isCheckAudioBitrate()) {
			failWithMessage("Expected at least one quality check to be enabled but all are disabled");
		}
		return this;
	}
}
