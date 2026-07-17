package io.metaloom.cortex.node.loom.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.loom.LoomNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link LoomNodeOptions}.
 */
public class LoomNodeOptionsAssert extends CortexNodeOptionsAssert {

	public LoomNodeOptionsAssert(LoomNodeOptions actual) {
		super(actual);
	}
}