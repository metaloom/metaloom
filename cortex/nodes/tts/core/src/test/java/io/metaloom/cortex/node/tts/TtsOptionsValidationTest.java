package io.metaloom.cortex.node.tts;

import static io.metaloom.cortex.node.tts.assertj.TtsNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class TtsOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		TtsNodeOptions options = new TtsNodeOptions();
		assertThat(options).isValid()
			.hasHost("localhost")
			.hasPort(9100)
			.hasLanguage("de")
			.hasVoice("Jakob");
	}

	@Test
	public void testCustomOptionsValid() {
		TtsNodeOptions options = new TtsNodeOptions()
			.setTtsHost("tts.internal")
			.setTtsPort(9200)
			.setLanguage("en")
			.setVoice("af_heart");
		assertThat(options).isValid()
			.hasHost("tts.internal")
			.hasPort(9200)
			.hasLanguage("en")
			.hasVoice("af_heart");
	}

	@Test
	public void testEmptyHostInvalid() {
		TtsNodeOptions options = new TtsNodeOptions().setTtsHost("");
		assertThat(options).isInvalid().hasError("ttsHost must not be empty");
	}

	@Test
	public void testNonPositivePortInvalid() {
		TtsNodeOptions options = new TtsNodeOptions().setTtsPort(0);
		assertThat(options).isInvalid().hasError("ttsPort must be positive, got 0");
	}

	@Test
	public void testEmptyLanguageInvalid() {
		TtsNodeOptions options = new TtsNodeOptions().setLanguage("");
		assertThat(options).isInvalid().hasError("language must not be empty");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		TtsNodeOptions options = new TtsNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative, got -1");
	}

	@Test
	public void testValidationResultDirect() {
		TtsNodeOptions valid = new TtsNodeOptions();
		ValidationResult validResult = valid.validate();
		assertThat(validResult).isValid().hasNoErrors();
	}
}
