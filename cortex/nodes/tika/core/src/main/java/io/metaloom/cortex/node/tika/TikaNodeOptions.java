package io.metaloom.cortex.node.tika;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class TikaNodeOptions extends AbstractNodeOptions<TikaNodeOptions> {

	@Override
	protected TikaNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		return validateCommon().isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(validateCommon());
	}
}
