package io.metaloom.cortex.node.scene;

import io.metaloom.cortex.api.option.node.AbstractNodeOptions;

public class SceneDetectionOptions extends AbstractNodeOptions<SceneDetectionOptions> {

	public static final String KEY = "scene-detector";

	@Override
	protected SceneDetectionOptions self() {
		return this;
	}

}
