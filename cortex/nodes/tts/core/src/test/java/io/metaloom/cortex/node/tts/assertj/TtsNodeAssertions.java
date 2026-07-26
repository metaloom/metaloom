package io.metaloom.cortex.node.tts.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.tts.TtsNodeOptions;

/**
 * Entry point for TTS node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the TTS options
 * assertions plus everything inherited from {@code NodeAssertions} (media, node
 * results), {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class TtsNodeAssertions extends NodeAssertions {

	public static TtsOptionsAssert assertThat(TtsNodeOptions actual) {
		return new TtsOptionsAssert(actual);
	}
}
