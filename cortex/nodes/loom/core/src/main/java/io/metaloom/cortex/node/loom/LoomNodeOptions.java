package io.metaloom.cortex.node.loom;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class LoomNodeOptions extends AbstractNodeOptions<LoomNodeOptions> {

	public static final String KEY = "loom";

	@Override
	protected LoomNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		return validateCommon().isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(validateCommon());
	}
}
