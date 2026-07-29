package io.metaloom.cortex.node.tts.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.tts.TtsNodeOptions;

/**
 * AssertJ assertions for {@link TtsNodeOptions}.
 */
public class TtsOptionsAssert extends AbstractCortexNodeOptionsAssert<TtsOptionsAssert, TtsNodeOptions> {

	public TtsOptionsAssert(TtsNodeOptions actual) {
		super(actual, TtsOptionsAssert.class);
	}

	public TtsOptionsAssert hasHost(String expected) {
		isNotNull();
		if (!expected.equals(actual.getTtsHost())) {
			failWithMessage("Expected ttsHost to be '%s' but was '%s'", expected, actual.getTtsHost());
		}
		return this;
	}

	public TtsOptionsAssert hasPort(int expected) {
		isNotNull();
		if (actual.getTtsPort() != expected) {
			failWithMessage("Expected ttsPort to be %d but was %d", expected, actual.getTtsPort());
		}
		return this;
	}

	public TtsOptionsAssert hasLanguage(String expected) {
		isNotNull();
		if (!expected.equals(actual.getLanguage())) {
			failWithMessage("Expected language to be '%s' but was '%s'", expected, actual.getLanguage());
		}
		return this;
	}

	public TtsOptionsAssert hasVoice(String expected) {
		isNotNull();
		if (!expected.equals(actual.getVoice())) {
			failWithMessage("Expected voice to be '%s' but was '%s'", expected, actual.getVoice());
		}
		return this;
	}
}
