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
	 * Assert that the keeper-exclude folder is set to the expected value.
	 */
	public DedupNodeOptionsAssert hasKeepExcludeFolder(Path expectedPath) {
		isNotNull();
		if (!expectedPath.equals(actual.getKeepExcludeFolder())) {
			failWithMessage("Expected keepExcludeFolder to be '%s' but was '%s'", expectedPath, actual.getKeepExcludeFolder());
		}
		return this;
	}

	/**
	 * Assert that no keeper-exclude folder is configured, which is the default and means the check is off.
	 */
	public DedupNodeOptionsAssert hasNoKeepExcludeFolder() {
		isNotNull();
		if (actual.getKeepExcludeFolder() != null) {
			failWithMessage("Expected keepExcludeFolder to be unset but it was '%s'", actual.getKeepExcludeFolder());
		}
		return this;
	}
}
