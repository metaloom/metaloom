package io.metaloom.cortex.node.metadata.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.metadata.MetadataNodeOptions;

/**
 * Entry point for metadata node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a metadata
 * test needs - it exposes the metadata assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}.</p>
 */
public class MetadataNodeAssertions extends NodeAssertions {

	public static MetadataNodeOptionsAssert assertThat(MetadataNodeOptions actual) {
		return new MetadataNodeOptionsAssert(actual);
	}
}
