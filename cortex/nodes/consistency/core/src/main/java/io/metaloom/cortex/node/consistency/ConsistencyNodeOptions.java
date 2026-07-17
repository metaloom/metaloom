package io.metaloom.cortex.node.consistency;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class ConsistencyNodeOptions extends AbstractNodeOptions<ConsistencyNodeOptions> {

	@Override
	protected ConsistencyNodeOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		return validateCommon().isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(validateCommon());
	}
}
