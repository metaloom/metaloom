package io.metaloom.cortex.node.dedup.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.dedup.DedupNodeOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

import java.nio.file.Path;

/**
 * AssertJ assertions for {@link DedupNodeOptions}.
 */
public class DedupNodeOptionsAssert extends CortexNodeOptionsAssert {

	public DedupNodeOptionsAssert(DedupNodeOptions actual) {
		super(actual);
	}

	/**
	 * Get the actual object as DedupNodeOptions.
	 */
	private DedupNodeOptions dedupOptions() {
		return (DedupNodeOptions) actual;
	}

	/**
	 * Assert that the dupFolder is set to the expected value.
	 */
	public DedupNodeOptionsAssert hasDupFolder(Path expectedPath) {
		isNotNull();
		if (!expectedPath.equals(dedupOptions().getDupFolder())) {
			failWithMessage("Expected dupFolder to be '%s' but was '%s'", expectedPath, dedupOptions().getDupFolder());
		}
		return this;
	}

	/**
	 * Assert that the dupFolder is not null.
	 */
	public DedupNodeOptionsAssert hasDupFolder() {
		isNotNull();
		if (dedupOptions().getDupFolder() == null) {
			failWithMessage("Expected dupFolder to be set but it was null");
		}
		return this;
	}
}