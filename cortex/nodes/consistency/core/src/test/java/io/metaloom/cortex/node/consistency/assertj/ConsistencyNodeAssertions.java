package io.metaloom.cortex.node.consistency.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.consistency.ConsistencyNodeOptions;

/**
 * Entry point for Consistency node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.consistency.assertj.ConsistencyNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class ConsistencyNodeAssertions extends Assertions {

	public static ConsistencyNodeOptionsAssert assertThat(ConsistencyNodeOptions actual) {
		return new ConsistencyNodeOptionsAssert(actual);
	}
}