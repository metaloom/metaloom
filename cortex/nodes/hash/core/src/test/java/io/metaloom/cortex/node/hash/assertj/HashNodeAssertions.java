package io.metaloom.cortex.node.hash.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.hash.HashNodeOptions;

/**
 * Entry point for Hash node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.hash.assertj.HashNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasMD5(true).hasSHA512(true);
 * </pre>
 */
public class HashNodeAssertions extends Assertions {

	public static HashNodeOptionsAssert assertThat(HashNodeOptions actual) {
		return new HashNodeOptionsAssert(actual);
	}
}