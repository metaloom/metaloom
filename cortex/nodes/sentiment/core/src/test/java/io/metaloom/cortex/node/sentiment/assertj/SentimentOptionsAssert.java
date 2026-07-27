package io.metaloom.cortex.node.sentiment.assertj;

import java.util.List;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.sentiment.SentimentNodeOptions;

/**
 * AssertJ assertions for {@link SentimentNodeOptions}.
 */
public class SentimentOptionsAssert extends AbstractCortexNodeOptionsAssert<SentimentOptionsAssert, SentimentNodeOptions> {

	public SentimentOptionsAssert(SentimentNodeOptions actual) {
		super(actual, SentimentOptionsAssert.class);
	}

	public SentimentOptionsAssert hasHost(String expected) {
		isNotNull();
		if (!expected.equals(actual.getSentimentHost())) {
			failWithMessage("Expected sentimentHost to be '%s' but was '%s'", expected, actual.getSentimentHost());
		}
		return this;
	}

	public SentimentOptionsAssert hasPort(int expected) {
		isNotNull();
		if (actual.getSentimentPort() != expected) {
			failWithMessage("Expected sentimentPort to be %d but was %d", expected, actual.getSentimentPort());
		}
		return this;
	}

	public SentimentOptionsAssert hasLanguage(String expected) {
		isNotNull();
		if (!expected.equals(actual.getLanguage())) {
			failWithMessage("Expected language to be '%s' but was '%s'", expected, actual.getLanguage());
		}
		return this;
	}

	public SentimentOptionsAssert hasTextSources(List<String> expected) {
		isNotNull();
		if (!expected.equals(actual.getTextSources())) {
			failWithMessage("Expected textSources to be %s but was %s", expected, actual.getTextSources());
		}
		return this;
	}

	public SentimentOptionsAssert hasMaxChars(int expected) {
		isNotNull();
		if (actual.getMaxChars() != expected) {
			failWithMessage("Expected maxChars to be %d but was %d", expected, actual.getMaxChars());
		}
		return this;
	}
}
