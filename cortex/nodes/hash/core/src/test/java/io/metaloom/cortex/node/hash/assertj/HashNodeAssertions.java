package io.metaloom.cortex.node.hash.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.hash.HashNodeOptions;

/**
 * Entry point for Hash node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a hash
 * test needs — it exposes the hash assertions plus everything inherited from
 * {@code NodeAssertions} (media, node results), {@code OptionsAssertions}
 * (generic options, validation results) and AssertJ's own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.hash.assertj.HashNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasMD5(true).hasSHA512(true);
 * </pre>
 */
public class HashNodeAssertions extends NodeAssertions {

	public static HashNodeOptionsAssert assertThat(HashNodeOptions actual) {
		return new HashNodeOptionsAssert(actual);
	}
}
