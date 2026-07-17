package io.metaloom.cortex.node.fp;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class FingerprintNodeOptions extends AbstractNodeOptions<FingerprintNodeOptions> {

	@Override
	protected FingerprintNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		return validateCommon().isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(validateCommon());
	}
}
