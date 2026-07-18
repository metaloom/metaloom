package io.metaloom.cortex.node.dedup.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.dedup.DedupNodeOptions;

/**
 * Entry point for Dedup node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a dedup
 * test needs — it exposes the dedup assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.dedup.assertj.DedupNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasDupFolder(Paths.get("duplicates"));
 * </pre>
 */
public class DedupNodeAssertions extends NodeAssertions {

	public static DedupNodeOptionsAssert assertThat(DedupNodeOptions actual) {
		return new DedupNodeOptionsAssert(actual);
	}
}
