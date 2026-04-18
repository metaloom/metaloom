package io.metaloom.cortex.node.scene;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.FS;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.meta.AbstractMetaStorage;
import io.metaloom.cortex.api.meta.MetaStorage;
import io.metaloom.cortex.media.scene.SceneDetectionResult;

@Singleton
public class SceneDetectionMetaStorage extends AbstractMetaStorage {

	public static final LoomMetaKey<SceneDetectionResult> SCENE_DETECTION_FLAG_KEY = metaKey("scene-detection-result", 1, FS,
		SceneDetectionResult.class);

	@Inject
	public SceneDetectionMetaStorage(MetaStorage storage) {
		super(storage);
	}

	public boolean hasSceneDetectionResult(LoomMedia media) {
		return has(media, SCENE_DETECTION_FLAG_KEY);
	}

	public SceneDetectionResult getSceneDetectionResult(LoomMedia media) {
		return get(media, SCENE_DETECTION_FLAG_KEY);
	}

	public void setSceneDetectionResult(LoomMedia media, SceneDetectionResult result) {
		put(media, SCENE_DETECTION_FLAG_KEY, result);
	}

}
