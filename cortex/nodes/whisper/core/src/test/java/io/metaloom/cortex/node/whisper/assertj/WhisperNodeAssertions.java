package io.metaloom.cortex.node.whisper.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.whisper.WhisperOptions;

/**
 * Entry point for Whisper node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.whisper.assertj.WhisperNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasModelPath("models/ggml-tiny.bin").hasUseGpu(true);
 * </pre>
 */
public class WhisperNodeAssertions extends Assertions {

	public static WhisperOptionsAssert assertThat(WhisperOptions actual) {
		return new WhisperOptionsAssert(actual);
	}
}