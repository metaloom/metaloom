package io.metaloom.cortex.node.scene.assertj;

import org.assertj.core.api.AbstractAssert;

import io.metaloom.cortex.node.scene.SceneDetectionOptions;
import io.metaloom.cortex.api.option.assertj.CortexNodeOptionsAssert;

/**
 * AssertJ assertions for {@link SceneDetectionOptions}.
 */
public class SceneDetectionOptionsAssert extends CortexNodeOptionsAssert {

	public SceneDetectionOptionsAssert(SceneDetectionOptions actual) {
		super(actual);
	}
}