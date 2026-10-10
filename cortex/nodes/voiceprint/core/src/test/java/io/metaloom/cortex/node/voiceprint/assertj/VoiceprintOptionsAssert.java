package io.metaloom.cortex.node.voiceprint.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.voiceprint.VoiceprintNodeOptions;

/**
 * AssertJ assertions for {@link VoiceprintNodeOptions}.
 */
public class VoiceprintOptionsAssert extends AbstractCortexNodeOptionsAssert<VoiceprintOptionsAssert, VoiceprintNodeOptions> {

	public VoiceprintOptionsAssert(VoiceprintNodeOptions actual) {
		super(actual, VoiceprintOptionsAssert.class);
	}

	public VoiceprintOptionsAssert hasHost(String expected) {
		isNotNull();
		if (!expected.equals(actual.getVoiceprintHost())) {
			failWithMessage("Expected voiceprintHost to be '%s' but was '%s'", expected, actual.getVoiceprintHost());
		}
		return this;
	}

	public VoiceprintOptionsAssert hasPort(int expected) {
		isNotNull();
		if (actual.getVoiceprintPort() != expected) {
			failWithMessage("Expected voiceprintPort to be %d but was %d", expected, actual.getVoiceprintPort());
		}
		return this;
	}

	public VoiceprintOptionsAssert hasModel(String expected) {
		isNotNull();
		if (!expected.equals(actual.getModel())) {
			failWithMessage("Expected model to be '%s' but was '%s'", expected, actual.getModel());
		}
		return this;
	}

	public VoiceprintOptionsAssert hasWindow(double window, double hop) {
		isNotNull();
		if (actual.getWindowSeconds() != window || actual.getHopSeconds() != hop) {
			failWithMessage("Expected window/hop %s/%s but was %s/%s", window, hop, actual.getWindowSeconds(), actual.getHopSeconds());
		}
		return this;
	}

	public VoiceprintOptionsAssert persistsWindows(boolean expected) {
		isNotNull();
		if (actual.isPersistWindows() != expected) {
			failWithMessage("Expected persistWindows to be %s but was %s", expected, actual.isPersistWindows());
		}
		return this;
	}
}
