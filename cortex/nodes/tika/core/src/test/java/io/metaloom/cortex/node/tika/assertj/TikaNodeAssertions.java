package io.metaloom.cortex.node.tika.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.node.tika.TikaNodeOptions;

/**
 * Entry point for Tika node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.node.tika.assertj.TikaNodeAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * </pre>
 */
public class TikaNodeAssertions extends Assertions {

	public static TikaNodeOptionsAssert assertThat(TikaNodeOptions actual) {
		return new TikaNodeOptionsAssert(actual);
	}
}