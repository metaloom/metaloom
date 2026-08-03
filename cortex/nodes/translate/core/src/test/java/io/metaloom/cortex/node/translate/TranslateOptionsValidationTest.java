package io.metaloom.cortex.node.translate;

import static io.metaloom.cortex.node.translate.assertj.TranslateNodeAssertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The {@code validate()} contract: the defaults are usable as they stand, and every way of breaking
 * a field is reported rather than discovered at run time on a worker.
 */
class TranslateOptionsValidationTest {

	@Test
	void testDefaultsAreValid() {
		TranslateNodeOptions options = new TranslateNodeOptions();

		assertThat(options)
			.isValid()
			.hasTargetLanguage("en")
			.hasSourceLanguage("auto")
			.hasModel("google/gemma-2-27b-it")
			.hasOpenaiUrl("http://127.0.0.1:8080/v1")
			.hasMaxChunkChars(8000)
			.hasMaxChars(200000);
	}

	@Test
	void testEmptyOpenaiUrlIsInvalid() {
		TranslateNodeOptions options = new TranslateNodeOptions();
		options.setOpenaiUrl("");

		assertThat(options).isInvalid().hasError("openaiUrl must not be empty").hasErrorCount(1);
	}

	@Test
	void testNullOpenaiUrlIsInvalid() {
		TranslateNodeOptions options = new TranslateNodeOptions();
		options.setOpenaiUrl(null);

		assertThat(options).isInvalid().hasError("openaiUrl must not be empty");
	}

	@Test
	void testNonPositiveContextWindowIsInvalid() {
		assertThat(new TranslateNodeOptions().setContextWindow(0))
			.isInvalid().hasError("contextWindow must be at least 1, got 0").hasErrorCount(1);
	}

	@Test
	void testBlankTargetLanguageIsInvalid() {
		assertThat(new TranslateNodeOptions().setTargetLanguage("  "))
			.isInvalid().hasError("targetLanguage must not be empty").hasErrorCount(1);
	}

	@Test
	void testBlankModelIsInvalid() {
		assertThat(new TranslateNodeOptions().setModel(""))
			.isInvalid().hasError("model must not be empty").hasErrorCount(1);
	}

	@Test
	void testBlankPromptTemplateIsInvalid() {
		assertThat(new TranslateNodeOptions().setPromptTemplate(""))
			.isInvalid().hasError("promptTemplate must not be empty").hasErrorCount(1);
	}

	@Test
	void testPromptTemplateWithoutTextPlaceholderIsInvalid() {
		// A template without ${text} sends the model an instruction and no document; it answers
		// something plausible about nothing at all, and that answer is stored as a translation.
		assertThat(new TranslateNodeOptions().setPromptTemplate("Translate into ${targetLanguage}."))
			.isInvalid().hasError("promptTemplate must contain ${text}").hasErrorCount(1);
	}

	@Test
	void testCustomPromptTemplateWithPlaceholderIsValid() {
		assertThat(new TranslateNodeOptions().setPromptTemplate("Uebersetze: ${text}")).isValid();
	}

	@Test
	void testTooSmallChunkBudgetIsInvalid() {
		assertThat(new TranslateNodeOptions().setMaxChunkChars(10))
			.isInvalid().hasError("maxChunkChars must be at least 200, got 10").hasErrorCount(1);
	}

	@Test
	void testNonPositiveMaxCharsIsInvalid() {
		assertThat(new TranslateNodeOptions().setMaxChars(0))
			.isInvalid().hasError("maxChars must be at least 1, got 0").hasErrorCount(1);
	}

	@Test
	void testNegativeTimeoutIsInvalid() {
		TranslateNodeOptions options = new TranslateNodeOptions();
		options.setTimeoutMs(-1);

		assertThat(options).isInvalid().hasError("timeoutMs must be non-negative, got -1");
	}

	@Test
	void testSeveralBrokenFieldsAreAllReported() {
		TranslateNodeOptions options = new TranslateNodeOptions()
			.setTargetLanguage("")
			.setModel("")
			.setMaxChars(0);
		options.setOpenaiUrl("");

		assertThat(options).isInvalid().hasErrorCount(4);
	}
}
