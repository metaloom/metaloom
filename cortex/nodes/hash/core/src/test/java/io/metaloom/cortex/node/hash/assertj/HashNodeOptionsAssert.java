package io.metaloom.cortex.node.hash.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.hash.HashNodeOptions;

/**
 * AssertJ assertions for {@link HashNodeOptions}.
 */
public class HashNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<HashNodeOptionsAssert, HashNodeOptions> {

	public HashNodeOptionsAssert(HashNodeOptions actual) {
		super(actual, HashNodeOptionsAssert.class);
	}

	/**
	 * Assert that MD5 is enabled/disabled.
	 */
	public HashNodeOptionsAssert hasMD5(boolean expected) {
		isNotNull();
		if (actual.isMD5() != expected) {
			failWithMessage("Expected MD5 to be %s but was %s", expected, actual.isMD5());
		}
		return this;
	}

	/**
	 * Assert that SHA256 is enabled/disabled.
	 */
	public HashNodeOptionsAssert hasSHA256(boolean expected) {
		isNotNull();
		if (actual.isSHA256() != expected) {
			failWithMessage("Expected SHA256 to be %s but was %s", expected, actual.isSHA256());
		}
		return this;
	}

	/**
	 * Assert that SHA512 is enabled/disabled.
	 */
	public HashNodeOptionsAssert hasSHA512(boolean expected) {
		isNotNull();
		if (actual.isSHA512() != expected) {
			failWithMessage("Expected SHA512 to be %s but was %s", expected, actual.isSHA512());
		}
		return this;
	}

	/**
	 * Assert that chunkHash is enabled/disabled.
	 */
	public HashNodeOptionsAssert hasChunkHash(boolean expected) {
		isNotNull();
		if (actual.isChunkHash() != expected) {
			failWithMessage("Expected chunkHash to be %s but was %s", expected, actual.isChunkHash());
		}
		return this;
	}

	/**
	 * Assert that at least one hash algorithm is enabled.
	 */
	public HashNodeOptionsAssert hasAtLeastOneAlgorithmEnabled() {
		isNotNull();
		if (!actual.isMD5() && !actual.isSHA256() && !actual.isSHA512() && !actual.isChunkHash()) {
			failWithMessage("Expected at least one hash algorithm to be enabled but all are disabled");
		}
		return this;
	}
}
