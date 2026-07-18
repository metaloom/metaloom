package io.metaloom.cortex.node.loom.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.loom.LoomNodeOptions;

/**
 * AssertJ assertions for {@link LoomNodeOptions}.
 */
public class LoomNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<LoomNodeOptionsAssert, LoomNodeOptions> {

	public LoomNodeOptionsAssert(LoomNodeOptions actual) {
		super(actual, LoomNodeOptionsAssert.class);
	}
}
