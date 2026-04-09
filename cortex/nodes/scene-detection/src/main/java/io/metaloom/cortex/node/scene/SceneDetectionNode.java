package io.metaloom.cortex.node.scene;

import static io.metaloom.cortex.api.node.ResultOrigin.COMPUTED;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.node.scene.impl.OpticalFlowSceneDetector;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.payload.Scene;
import io.metaloom.cortex.api.node.payload.ScenesPayload;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.common.node.AbstractMediaNode;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.video4j.VideoFile;

public class SceneDetectionNode extends AbstractMediaNode<ScenesPayload, SceneDetectionOptions> {

	public static final Logger log = LoggerFactory.getLogger(SceneDetectionNode.class);

	public static final String OUTPUT_SCENE_DETECTION = "scene_detection";

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
	protected NodeResult<ScenesPayload> compute(NodeContext<LoomMedia> ctx, AssetResponse asset) throws IOException {
		LoomMedia media = ctx.media();
		if (media.isVideo()) {
			VideoFile video = VideoFile.open(media.path());
			SceneDetectionResult result = detector.detect(video);
			ctx.output(OUTPUT_SCENE_DETECTION, result.toString());

			List<Scene> scenes = new ArrayList<>();
			for (io.metaloom.cortex.media.scene.Scene s : result.scenes()) {
				scenes.add(new Scene(0, 0, s.getFrom(), s.getTo()));
			}
			return ctx.origin(COMPUTED).next(ScenesPayload.of(scenes));
		} else {
			return ctx.skipped("no video media").next();
		}
	}

}
