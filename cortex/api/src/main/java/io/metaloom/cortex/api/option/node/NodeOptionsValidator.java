package io.metaloom.cortex.api.option.node;

import java.util.List;

/**
 * Interface for validating node options. Implementations should check all
 * configuration fields and return a list of validation errors. An empty list
 * means the options are valid.
 */
@FunctionalInterface
public interface NodeOptionsValidator<T extends CortexNodeOptions> {

	/**
	 * Validate the given options instance.
	 * 
	 * @param options the options to validate
	 * @return list of validation error messages, empty if valid
	 */
	List<String> validate(T options);

	/**
	 * Default validator that returns an empty list (no validation).
	 */
	static <T extends CortexNodeOptions> NodeOptionsValidator<T> noOp() {
		return options -> List.of();
	}
}
