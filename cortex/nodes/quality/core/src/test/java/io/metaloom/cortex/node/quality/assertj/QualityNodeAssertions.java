package io.metaloom.cortex.node.quality.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.quality.QualityNodeOptions;

/**
 * Entry point for Quality node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a quality
 * test needs — it exposes the quality assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.quality.assertj.QualityNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasCheckBlurriness(true).hasCheckResolution(true);
 * </pre>
 */
public class QualityNodeAssertions extends NodeAssertions {

	public static QualityNodeOptionsAssert assertThat(QualityNodeOptions actual) {
		return new QualityNodeOptionsAssert(actual);
	}
}
