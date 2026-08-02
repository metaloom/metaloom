package io.metaloom.cortex.node.translate.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.translate.TranslateNodeOptions;

/**
 * AssertJ assertions for {@link TranslateNodeOptions}.
 */
public class TranslateOptionsAssert extends AbstractCortexNodeOptionsAssert<TranslateOptionsAssert, TranslateNodeOptions> {

	public TranslateOptionsAssert(TranslateNodeOptions actual) {
		super(actual, TranslateOptionsAssert.class);
	}

	public TranslateOptionsAssert hasTargetLanguage(String expected) {
		isNotNull();
		if (!expected.equals(actual.getTargetLanguage())) {
			failWithMessage("Expected targetLanguage to be '%s' but was '%s'", expected, actual.getTargetLanguage());
		}
		return this;
	}

	public TranslateOptionsAssert hasSourceLanguage(String expected) {
		isNotNull();
		if (!expected.equals(actual.getSourceLanguage())) {
			failWithMessage("Expected sourceLanguage to be '%s' but was '%s'", expected, actual.getSourceLanguage());
		}
		return this;
	}

	public TranslateOptionsAssert hasModel(String expected) {
		isNotNull();
		if (!expected.equals(actual.getModel())) {
			failWithMessage("Expected model to be '%s' but was '%s'", expected, actual.getModel());
		}
		return this;
	}

	public TranslateOptionsAssert hasOllamaUrl(String expected) {
		isNotNull();
		if (!expected.equals(actual.ollamaUrl())) {
			failWithMessage("Expected ollamaUrl to be '%s' but was '%s'", expected, actual.ollamaUrl());
		}
		return this;
	}

	public TranslateOptionsAssert hasMaxChunkChars(int expected) {
		isNotNull();
		if (actual.getMaxChunkChars() != expected) {
			failWithMessage("Expected maxChunkChars to be %d but was %d", expected, actual.getMaxChunkChars());
		}
		return this;
	}

	public TranslateOptionsAssert hasMaxChars(int expected) {
		isNotNull();
		if (actual.getMaxChars() != expected) {
			failWithMessage("Expected maxChars to be %d but was %d", expected, actual.getMaxChars());
		}
		return this;
	}
}
