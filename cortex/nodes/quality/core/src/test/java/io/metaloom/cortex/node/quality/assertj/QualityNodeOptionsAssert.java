package io.metaloom.cortex.node.quality.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.quality.QualityNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link QualityNodeOptions}.
 */
public class QualityNodeOptionsAssert extends CortexNodeOptionsAssert {

	public QualityNodeOptionsAssert(QualityNodeOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as QualityNodeOptions.
	 */
	private QualityNodeOptions qualityOptions() {
		return (QualityNodeOptions) actual;
	}

	/**
	 * Assert that blurriness check is enabled/disabled.
	 */
	public QualityNodeOptionsAssert hasCheckBlurriness(boolean expected) {
		isNotNull();
		if (qualityOptions().isCheckBlurriness() != expected) {
			failWithMessage("Expected checkBlurriness to be %s but was %s", expected, qualityOptions().isCheckBlurriness());
		}
		return this;
	}

	/**
	 * Assert that resolution check is enabled/disabled.
	 */
	public QualityNodeOptionsAssert hasCheckResolution(boolean expected) {
		isNotNull();
		if (qualityOptions().isCheckResolution() != expected) {
			failWithMessage("Expected checkResolution to be %s but was %s", expected, qualityOptions().isCheckResolution());
		}
		return this;
	}

	/**
	 * Assert that video bitrate check is enabled/disabled.
	 */
	public QualityNodeOptionsAssert hasCheckVideoBitrate(boolean expected) {
		isNotNull();
		if (qualityOptions().isCheckVideoBitrate() != expected) {
			failWithMessage("Expected checkVideoBitrate to be %s but was %s", expected, qualityOptions().isCheckVideoBitrate());
		}
		return this;
	}

	/**
	 * Assert that audio bitrate check is enabled/disabled.
	 */
	public QualityNodeOptionsAssert hasCheckAudioBitrate(boolean expected) {
		isNotNull();
		if (qualityOptions().isCheckAudioBitrate() != expected) {
			failWithMessage("Expected checkAudioBitrate to be %s but was %s", expected, qualityOptions().isCheckAudioBitrate());
		}
		return this;
	}

	/**
	 * Assert that at least one quality check is enabled.
	 */
	public QualityNodeOptionsAssert hasAtLeastOneCheckEnabled() {
		isNotNull();
		if (!qualityOptions().isCheckBlurriness() && !qualityOptions().isCheckResolution() && !qualityOptions().isCheckVideoBitrate() && !qualityOptions().isCheckAudioBitrate()) {
			failWithMessage("Expected at least one quality check to be enabled but all are disabled");
		}
		return this;
	}
}