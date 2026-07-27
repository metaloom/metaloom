package io.metaloom.cortex.node.sentiment.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.sentiment.SentimentNodeOptions;

/**
 * Entry point for sentiment node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the sentiment
 * options assertions plus everything inherited from {@code NodeAssertions} (media,
 * node results), {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class SentimentNodeAssertions extends NodeAssertions {

	public static SentimentOptionsAssert assertThat(SentimentNodeOptions actual) {
		return new SentimentOptionsAssert(actual);
	}
}
