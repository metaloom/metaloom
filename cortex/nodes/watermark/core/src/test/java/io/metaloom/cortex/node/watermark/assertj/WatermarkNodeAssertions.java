package io.metaloom.cortex.node.watermark.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.watermark.WatermarkNodeOptions;

/**
 * Entry point for watermark node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the watermark options assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class WatermarkNodeAssertions extends NodeAssertions {

	public static WatermarkOptionsAssert assertThat(WatermarkNodeOptions actual) {
		return new WatermarkOptionsAssert(actual);
	}
}
