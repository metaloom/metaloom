package io.metaloom.cortex.node.voiceprint.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.voiceprint.VoiceprintNodeOptions;

/**
 * Entry point for voiceprint node AssertJ assertions. Extends {@link NodeAssertions}, so one static import exposes the options assertions plus
 * everything inherited (media, node results, options, AssertJ itself).
 */
public class VoiceprintNodeAssertions extends NodeAssertions {

	public static VoiceprintOptionsAssert assertThat(VoiceprintNodeOptions actual) {
		return new VoiceprintOptionsAssert(actual);
	}
}
