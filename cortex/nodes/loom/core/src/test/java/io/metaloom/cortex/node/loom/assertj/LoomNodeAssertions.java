package io.metaloom.cortex.node.loom.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.loom.LoomNodeOptions;

/**
 * Entry point for Loom node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a loom
 * test needs — it exposes the loom assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.loom.assertj.LoomNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class LoomNodeAssertions extends NodeAssertions {

	public static LoomNodeOptionsAssert assertThat(LoomNodeOptions actual) {
		return new LoomNodeOptionsAssert(actual);
	}
}
