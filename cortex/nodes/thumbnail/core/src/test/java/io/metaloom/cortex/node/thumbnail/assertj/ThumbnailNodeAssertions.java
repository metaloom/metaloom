package io.metaloom.cortex.node.thumbnail.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;

/**
 * Entry point for Thumbnail node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.thumbnail.assertj.ThumbnailNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasTileSize(384).hasCols(6).hasRows(1);
 * </pre>
 */
public class ThumbnailNodeAssertions extends Assertions {

	public static ThumbnailNodeOptionsAssert assertThat(ThumbnailNodeOptions actual) {
		return new ThumbnailNodeOptionsAssert(actual);
	}
}