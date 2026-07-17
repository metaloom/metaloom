package io.metaloom.cortex.node.fp.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.fp.FingerprintNodeOptions;

/**
 * Entry point for Fingerprint node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.fp.assertj.FingerprintNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class FingerprintNodeAssertions extends Assertions {

	public static FingerprintNodeOptionsAssert assertThat(FingerprintNodeOptions actual) {
		return new FingerprintNodeOptionsAssert(actual);
	}
}