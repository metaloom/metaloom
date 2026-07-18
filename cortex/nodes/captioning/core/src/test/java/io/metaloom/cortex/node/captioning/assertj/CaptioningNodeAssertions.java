package io.metaloom.cortex.node.captioning.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.captioning.CaptioningNodeOptions;

/**
 * Entry point for Captioning node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a
 * captioning test needs — it exposes the captioning assertions plus everything
 * inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} (generic options, validation results) and AssertJ's
 * own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.captioning.assertj.CaptioningNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasSmolVLMHost("localhost").hasSmolVLMPort(8000);
 * </pre>
 */
public class CaptioningNodeAssertions extends NodeAssertions {

	public static CaptioningNodeOptionsAssert assertThat(CaptioningNodeOptions actual) {
		return new CaptioningNodeOptionsAssert(actual);
	}
}
