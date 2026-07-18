package io.metaloom.cortex.node.tika.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.tika.TikaNodeOptions;

/**
 * AssertJ assertions for {@link TikaNodeOptions}.
 */
public class TikaNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<TikaNodeOptionsAssert, TikaNodeOptions> {

	public TikaNodeOptionsAssert(TikaNodeOptions actual) {
		super(actual, TikaNodeOptionsAssert.class);
	}
}
