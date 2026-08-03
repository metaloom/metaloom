package io.metaloom.cortex.node.metadata.assertj;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.metadata.MetadataNodeOptions;

/**
 * AssertJ assertions for {@link MetadataNodeOptions}.
 */
public class MetadataNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<MetadataNodeOptionsAssert, MetadataNodeOptions> {

	public MetadataNodeOptionsAssert(MetadataNodeOptions actual) {
		super(actual, MetadataNodeOptionsAssert.class);
	}
}
