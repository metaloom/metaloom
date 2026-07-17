package io.metaloom.cortex.node.scene;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.api.option.node.ValidationResult;

public class SceneDetectionOptions extends AbstractNodeOptions<SceneDetectionOptions> {

	public static final String KEY = "scene-detector";

	@Override
	protected SceneDetectionOptions self() {
		return this;
	}

	@Override
	public ValidationResult validate() {
		return validateCommon().isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(validateCommon());
	}
}
