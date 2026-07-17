package io.metaloom.cortex.node.hash.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.hash.HashNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link HashNodeOptions}.
 */
public class HashNodeOptionsAssert extends CortexNodeOptionsAssert {

	public HashNodeOptionsAssert(HashNodeOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as HashNodeOptions.
	 */
	private HashNodeOptions hashOptions() {
		return (HashNodeOptions) actual;
	}

	/**
	 * Assert that MD5 is enabled/disabled.
	 */
	public HashNodeOptionsAssert hasMD5(boolean expected) {
		isNotNull();
		if (hashOptions().isMD5() != expected) {
			failWithMessage("Expected MD5 to be %s but was %s", expected, hashOptions().isMD5());
		}
		return this;
	}

	/**
	 * Assert that SHA256 is enabled/disabled.
	 */
	public HashNodeOptionsAssert hasSHA256(boolean expected) {
		isNotNull();
		if (hashOptions().isSHA256() != expected) {
			failWithMessage("Expected SHA256 to be %s but was %s", expected, hashOptions().isSHA256());
		}
		return this;
	}

	/**
	 * Assert that SHA512 is enabled/disabled.
	 */
	public HashNodeOptionsAssert hasSHA512(boolean expected) {
		isNotNull();
		if (hashOptions().isSHA512() != expected) {
			failWithMessage("Expected SHA512 to be %s but was %s", expected, hashOptions().isSHA512());
		}
		return this;
	}

	/**
	 * Assert that chunkHash is enabled/disabled.
	 */
	public HashNodeOptionsAssert hasChunkHash(boolean expected) {
		isNotNull();
		if (hashOptions().isChunkHash() != expected) {
			failWithMessage("Expected chunkHash to be %s but was %s", expected, hashOptions().isChunkHash());
		}
		return this;
	}

	/**
	 * Assert that at least one hash algorithm is enabled.
	 */
	public HashNodeOptionsAssert hasAtLeastOneAlgorithmEnabled() {
		isNotNull();
		if (!hashOptions().isMD5() && !hashOptions().isSHA256() && !hashOptions().isSHA512() && !hashOptions().isChunkHash()) {
			failWithMessage("Expected at least one hash algorithm to be enabled but all are disabled");
		}
		return this;
	}
}