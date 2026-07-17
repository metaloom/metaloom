package io.metaloom.cortex.node.fp.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.fp.FingerprintNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link FingerprintNodeOptions}.
 */
public class FingerprintNodeOptionsAssert extends CortexNodeOptionsAssert {

	public FingerprintNodeOptionsAssert(FingerprintNodeOptions actual) {
		super(actual);
	}
}