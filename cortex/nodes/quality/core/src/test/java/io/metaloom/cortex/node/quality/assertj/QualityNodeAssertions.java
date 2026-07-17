package io.metaloom.cortex.node.quality.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.quality.QualityNodeOptions;

/**
 * Entry point for Quality node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.quality.assertj.QualityNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasCheckBlurriness(true).hasCheckResolution(true);
 * </pre>
 */
public class QualityNodeAssertions extends Assertions {

	public static QualityNodeOptionsAssert assertThat(QualityNodeOptions actual) {
		return new QualityNodeOptionsAssert(actual);
	}
}