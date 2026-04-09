package io.metaloom.cortex.node.scene;

import static io.metaloom.cortex.api.media.LoomMetaKey.metaKey;
import static io.metaloom.cortex.api.media.type.LoomMetaCoreType.FS;
import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.io.IOException;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.media.LoomMetaKey;
import io.metaloom.cortex.api.node.NodeOutputKey;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.node.scene.impl.OpticalFlowSceneDetector;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.video4j.VideoFile;

public class SceneDetectionNode extends AbstractMediaNode<SceneDetectionOptions> {

	public static final Logger log = LoggerFactory.getLogger(SceneDetectionNode.class);

	public static final NodeOutputKey<String> OUTPUT_SCENE_DETECTION = NodeOutputKey.of("scene_detection", String.class);

	public static final LoomMetaKey<SceneDetectionResult> SCENE_DETECTION_FLAG_KEY = metaKey("scene-detection-result", 1, FS,
		SceneDetectionResult.class);

	private OpticalFlowSceneDetector detector = new OpticalFlowSceneDetector();

	@Inject
	public SceneDetectionNode(@Nullable LoomClient client, CortexOptions cortexOptions, SceneDetectionOptions options) {
		super(client, cortexOptions, options);
	}

	@Override
	public String name() {
		return "scene-detection";
	}

	@Override
	protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
		if (options().isEnabled()) {
			return ctx.media().isVideo();
		} else {
			return false;
		}
	}

	@Override
	protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		if (media.isVideo()) {
			VideoFile video = VideoFile.open(media.path());
			SceneDetectionResult result = detector.detect(video);
			ctx.output(OUTPUT_SCENE_DETECTION, result.toString());
			return ctx.origin(COMPUTED).next();
		} else {
			return ctx.skipped("no video media").next();
		}
	}

}
