package io.metaloom.cortex.node.captioning.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.captioning.CaptioningNodeOptions;

/**
 * Entry point for Captioning node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.captioning.assertj.CaptioningNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasSmolVLMHost("localhost").hasSmolVLMPort(8000);
 * </pre>
 */
public class CaptioningNodeAssertions extends Assertions {

	public static CaptioningNodeOptionsAssert assertThat(CaptioningNodeOptions actual) {
		return new CaptioningNodeOptionsAssert(actual);
	}
}