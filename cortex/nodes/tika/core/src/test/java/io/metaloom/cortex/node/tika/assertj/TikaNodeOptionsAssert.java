package io.metaloom.cortex.node.tika.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.tika.TikaNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link TikaNodeOptions}.
 */
public class TikaNodeOptionsAssert extends CortexNodeOptionsAssert {

	public TikaNodeOptionsAssert(TikaNodeOptions actual) {
		super(actual);
	}
}