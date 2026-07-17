package io.metaloom.cortex.node.dedup.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.dedup.DedupNodeOptions;

/**
 * Entry point for Dedup node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.dedup.assertj.DedupNodeAssertions.assertThat;
 *
 * assertThat(options).isValid().hasDupFolder(Paths.get("duplicates"));
 * </pre>
 */
public class DedupNodeAssertions extends Assertions {

	public static DedupNodeOptionsAssert assertThat(DedupNodeOptions actual) {
		return new DedupNodeOptionsAssert(actual);
	}
}