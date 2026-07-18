package io.metaloom.cortex.node.consistency.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.consistency.ConsistencyNodeOptions;

/**
 * Entry point for Consistency node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a
 * consistency test needs — it exposes the consistency assertions plus
 * everything inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} (generic options, validation results) and AssertJ's
 * own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.consistency.assertj.ConsistencyNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class ConsistencyNodeAssertions extends NodeAssertions {

	public static ConsistencyNodeOptionsAssert assertThat(ConsistencyNodeOptions actual) {
		return new ConsistencyNodeOptionsAssert(actual);
	}
}
