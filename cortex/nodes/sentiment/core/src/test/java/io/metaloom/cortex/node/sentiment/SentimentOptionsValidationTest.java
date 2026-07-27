package io.metaloom.cortex.node.sentiment;

import static io.metaloom.cortex.node.sentiment.assertj.SentimentNodeAssertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.node.ValidationResult;

public class SentimentOptionsValidationTest {

	@Test
	public void testDefaultOptionsValid() {
		SentimentNodeOptions options = new SentimentNodeOptions();
		assertThat(options).isValid()
			.hasHost("localhost")
			.hasPort(9110)
			.hasLanguage("auto")
			.hasMaxChars(200_000)
			.hasTextSources(SentimentNodeOptions.DEFAULT_TEXT_SOURCES);
	}

	@Test
	public void testCustomOptionsValid() {
		SentimentNodeOptions options = new SentimentNodeOptions()
			.setSentimentHost("sentiment.internal")
			.setSentimentPort(9300)
			.setLanguage("de")
			.setMaxChars(5_000)
			.setTextSources(List.of("whisper:whisper_text"));
		assertThat(options).isValid()
			.hasHost("sentiment.internal")
			.hasPort(9300)
			.hasLanguage("de")
			.hasMaxChars(5_000)
			.hasTextSources(List.of("whisper:whisper_text"));
	}

	@Test
	public void testEmptyHostInvalid() {
		SentimentNodeOptions options = new SentimentNodeOptions().setSentimentHost("");
		assertThat(options).isInvalid().hasError("sentimentHost must not be empty");
	}

	@Test
	public void testNonPositivePortInvalid() {
		SentimentNodeOptions options = new SentimentNodeOptions().setSentimentPort(0);
		assertThat(options).isInvalid().hasError("sentimentPort must be positive, got 0");
	}

	@Test
	public void testEmptyLanguageInvalid() {
		SentimentNodeOptions options = new SentimentNodeOptions().setLanguage("");
		assertThat(options).isInvalid().hasError("language must not be empty");
	}

	@Test
	public void testEmptyTextSourcesInvalid() {
		SentimentNodeOptions options = new SentimentNodeOptions().setTextSources(List.of());
		assertThat(options).isInvalid().hasError("textSources must not be empty");
	}

	@Test
	public void testMalformedTextSourceInvalid() {
		SentimentNodeOptions options = new SentimentNodeOptions().setTextSources(List.of("tika"));
		assertThat(options).isInvalid().hasError("textSources entry must have the form 'nodeId:outputKey', got 'tika'");
	}

	@Test
	public void testTextSourceWithEmptyOutputKeyInvalid() {
		SentimentNodeOptions options = new SentimentNodeOptions().setTextSources(List.of("tika:"));
		assertThat(options).isInvalid().hasError("textSources entry must have the form 'nodeId:outputKey', got 'tika:'");
	}

	@Test
	public void testNonPositiveMaxCharsInvalid() {
		SentimentNodeOptions options = new SentimentNodeOptions().setMaxChars(0);
		assertThat(options).isInvalid().hasError("maxChars must be positive, got 0");
	}

	@Test
	public void testNegativeTimeoutInvalid() {
		SentimentNodeOptions options = new SentimentNodeOptions();
		options.setTimeoutMs(-1);
		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative, got -1");
	}

	@Test
	public void testValidationResultDirect() {
		ValidationResult result = new SentimentNodeOptions().validate();
		assertThat(result).isValid().hasNoErrors();
	}
}
