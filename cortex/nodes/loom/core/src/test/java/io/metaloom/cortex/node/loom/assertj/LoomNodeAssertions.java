package io.metaloom.cortex.node.loom.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.loom.LoomNodeOptions;

/**
 * Entry point for Loom node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.loom.assertj.LoomNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class LoomNodeAssertions extends Assertions {

	public static LoomNodeOptionsAssert assertThat(LoomNodeOptions actual) {
		return new LoomNodeOptionsAssert(actual);
	}
}