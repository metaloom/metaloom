package io.metaloom.cortex.node.tag.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.tag.TagNodeOptions;

/**
 * Entry point for tag node AssertJ assertions.
 *
 * <p>
 * Extends {@link NodeAssertions}, so this one static import exposes the tag options assertions plus
 * everything inherited from {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * and AssertJ's own {@code Assertions}.
 * </p>
 */
public class TagNodeAssertions extends NodeAssertions {

	public static TagOptionsAssert assertThat(TagNodeOptions actual) {
		return new TagOptionsAssert(actual);
	}
}
