package io.metaloom.cortex.node.consistency.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.consistency.ConsistencyNodeOptions;

/**
 * AssertJ assertions for {@link ConsistencyNodeOptions}.
 */
public class ConsistencyNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<ConsistencyNodeOptionsAssert, ConsistencyNodeOptions> {

	public ConsistencyNodeOptionsAssert(ConsistencyNodeOptions actual) {
		super(actual, ConsistencyNodeOptionsAssert.class);
	}
}
