package io.metaloom.cortex.node.tika.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.tika.TikaNodeOptions;

/**
 * Entry point for Tika node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a tika
 * test needs — it exposes the tika assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.tika.assertj.TikaNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class TikaNodeAssertions extends NodeAssertions {

	public static TikaNodeOptionsAssert assertThat(TikaNodeOptions actual) {
		return new TikaNodeOptionsAssert(actual);
	}
}
