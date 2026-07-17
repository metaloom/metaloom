package io.metaloom.cortex.node.consistency.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.consistency.ConsistencyNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link ConsistencyNodeOptions}.
 */
public class ConsistencyNodeOptionsAssert extends CortexNodeOptionsAssert {

	public ConsistencyNodeOptionsAssert(ConsistencyNodeOptions actual) {
		super(actual);
	}
}