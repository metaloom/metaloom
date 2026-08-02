package io.metaloom.cortex.node.translate.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.translate.TranslateNodeOptions;

/**
 * Entry point for translate node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the translate options
 * assertions plus everything inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} and AssertJ's own {@code Assertions}.
 * </p>
 */
public class TranslateNodeAssertions extends NodeAssertions {

	public static TranslateOptionsAssert assertThat(TranslateNodeOptions actual) {
		return new TranslateOptionsAssert(actual);
	}
}
