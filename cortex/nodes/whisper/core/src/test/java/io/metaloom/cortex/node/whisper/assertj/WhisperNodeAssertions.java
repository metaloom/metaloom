package io.metaloom.cortex.node.whisper.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.whisper.WhisperOptions;

/**
 * Entry point for Whisper node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a whisper
 * test needs — it exposes the whisper assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.whisper.assertj.WhisperNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasModelPath("models/ggml-tiny.bin").hasUseGpu(true);
 * </pre>
 */
public class WhisperNodeAssertions extends NodeAssertions {

	public static WhisperOptionsAssert assertThat(WhisperOptions actual) {
		return new WhisperOptionsAssert(actual);
	}
}
