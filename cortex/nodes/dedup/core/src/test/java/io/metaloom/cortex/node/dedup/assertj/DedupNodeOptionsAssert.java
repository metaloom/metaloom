package io.metaloom.cortex.node.dedup.assertj;

import java.nio.file.Path;

import io.metaloom.cortex.api.option.assertj.AbstractCortexNodeOptionsAssert;
import io.metaloom.cortex.node.dedup.DedupNodeOptions;

/**
 * AssertJ assertions for {@link DedupNodeOptions}.
 */
public class DedupNodeOptionsAssert extends AbstractCortexNodeOptionsAssert<DedupNodeOptionsAssert, DedupNodeOptions> {

	public DedupNodeOptionsAssert(DedupNodeOptions actual) {
		super(actual, DedupNodeOptionsAssert.class);
	}

	/**
	 * Assert that the dupFolder is set to the expected value.
	 */
	public DedupNodeOptionsAssert hasDupFolder(Path expectedPath) {
		isNotNull();
		if (!expectedPath.equals(actual.getDupFolder())) {
			failWithMessage("Expected dupFolder to be '%s' but was '%s'", expectedPath, actual.getDupFolder());
		}
		return this;
	}

	/**
	 * Assert that the dupFolder is not null.
	 */
	public DedupNodeOptionsAssert hasDupFolder() {
		isNotNull();
		if (actual.getDupFolder() == null) {
			failWithMessage("Expected dupFolder to be set but it was null");
		}
		return this;
	}
}
