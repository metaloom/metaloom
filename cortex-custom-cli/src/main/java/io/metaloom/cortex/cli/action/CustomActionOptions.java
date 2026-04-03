package io.metaloom.cortex.cli.action;

import io.metaloom.cortex.api.option.action.AbstractActionOptions;

public class CustomActionOptions extends AbstractActionOptions<CustomActionOptions> {

	public static final String KEY = "custom";

	@Override
	protected CustomActionOptions self() {
		return this;
	}
}
