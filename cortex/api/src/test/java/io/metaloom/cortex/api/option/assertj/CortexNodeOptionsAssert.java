package io.metaloom.cortex.api.option.assertj;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;

/**
 * AssertJ assertions for {@link CortexNodeOptions}.
 *
 * <p>This is the concrete leaf used when the options type is not known more
 * precisely. Node modules do not extend this class — they extend
 * {@link AbstractCortexNodeOptionsAssert} directly so their own assertions stay
 * chainable.</p>
 */
public class CortexNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<CortexNodeOptionsAssert, CortexNodeOptions> {

	public CortexNodeOptionsAssert(CortexNodeOptions actual) {
		super(actual, CortexNodeOptionsAssert.class);
	}
}
