package io.metaloom.cortex.node.fp.assertj;

import io.metaloom.cortex.media.test.assertj.NodeAssertions;
import io.metaloom.cortex.node.fp.FingerprintNodeOptions;

/**
 * Entry point for Fingerprint node AssertJ assertions.
 *
 * <p>Extends {@link NodeAssertions}, so this one static import is all a
 * fingerprint test needs — it exposes the fingerprint assertions plus
 * everything inherited from {@code NodeAssertions} (media, node results),
 * {@code OptionsAssertions} (generic options, validation results) and AssertJ's
 * own {@code Assertions}:</p>
 *
 * <pre>
 * import static io.metaloom.cortex.node.fp.assertj.FingerprintNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class FingerprintNodeAssertions extends NodeAssertions {

	public static FingerprintNodeOptionsAssert assertThat(FingerprintNodeOptions actual) {
		return new FingerprintNodeOptionsAssert(actual);
	}
}
