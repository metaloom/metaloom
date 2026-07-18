package io.metaloom.cortex.node.thumbnail.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.thumbnail.ThumbnailNodeOptions;

/**
 * Entry point for Thumbnail node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a
 * thumbnail test needs — it exposes the thumbnail assertions plus everything
 * inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} (generic options, validation results) and AssertJ's
 * own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.thumbnail.assertj.ThumbnailNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasTileSize(384).hasCols(6).hasRows(1);
 * </pre>
 */
public class ThumbnailNodeAssertions extends NodeAssertions {

	public static ThumbnailNodeOptionsAssert assertThat(ThumbnailNodeOptions actual) {
		return new ThumbnailNodeOptionsAssert(actual);
	}
}
