package io.metaloom.cortex.api.option.assertj;

import org.assertj.core.api.Assertions;

import io.metaloom.cortex.api.option.node.CortexNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

/**
 * Entry point for node options AssertJ assertions.
 *
 * <p>Usage:
 * <pre>
 * import static io.metaloom.cortex.api.option.assertj.OptionsAssertions.assertThat;
 *
 * assertThat(options).isValid();
 * assertThat(options).hasError("modelPath must not be empty");
 * </pre>
 */
public class OptionsAssertions extends Assertions {

	public static CortexNodeOptionsAssert assertThat(CortexNodeOptions actual) {
		return new CortexNodeOptionsAssert(actual);
	}

	public static ValidationResultAssert assertThat(ValidationResult actual) {
		return new ValidationResultAssert(actual);
	}
}