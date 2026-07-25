package io.metaloom.cortex.node.videocaptioning;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.media.scene.Scene;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.node.scene.impl.OpticalFlowSceneDetector;
import io.metaloom.cortex.node.videocaptioning.VideoCaptionOutput.SceneCaption;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.video4j.VideoFile;

/**
 * Variant B - scene-first. A dedicated scene-segmentation model (optical-flow {@link OpticalFlowSceneDetector}) cuts the video into shots up front, then a
 * regular image/multi-image VLM captions each shot independently. This yields a per-scene caption timeline instead of one whole-video description, and is the
 * "regular VLM + scene segmentation model" combination. Works on any OpenAI-compatible backend.
 *
 * <p>The overall {@code caption} is the labelled concatenation of the per-scene captions; the structured per-scene breakdown is carried in
 * {@link VideoCaptionOutput#scenes()} (persisted as a {@code scenes} array).
 */
public class SceneVideoCaptioningNode extends AbstractVideoCaptioningNode {

	private final OpticalFlowSceneDetector detector = new OpticalFlowSceneDetector();

	@Inject
	public SceneVideoCaptioningNode(@Nullable LoomClient client, CortexOptions cortexOptions, VideoCaptioningNodeOptions options, VideoVLMClient vlm) {
		super(client, cortexOptions, options, vlm);
	}

	@Override
	public String name() {
		return "video-captioning-scene";
	}

	@Override
	protected VideoCaptionOutput doCaption(VideoFile video) throws Exception {
		VideoCaptioningNodeOptions o = options();

		// 1) Segment the video into scenes up front.
		SceneDetectionResult detection = detector.detect(video);
		List<Scene> scenes = detection.scenes();
		if (scenes.isEmpty()) {
			// Whole clip is one scene.
			scenes = List.of(new Scene(0, Math.max(0, video.length() - 1)));
		}

		// 2) Caption each scene with the VLM, sampling a few representative frames per scene.
		List<SceneCaption> sceneCaptions = new ArrayList<>();
		long totalLatency = 0;
		int totalFrames = 0;
		int seq = 0;
		int limit = Math.min(scenes.size(), o.getMaxScenes());
		int perScene = Math.max(2, Math.min(o.getFrameCount(), 4));
		for (int i = 0; i < limit; i++) {
			Scene scene = scenes.get(i);
			List<BufferedImage> frames = FrameSampler.sampleRange(video, scene.getFrom(), scene.getTo(), perScene, o.getTargetFrameSize());
			if (frames.isEmpty()) {
				continue;
			}
			CaptionResult result = vlm.captionFrames(frames, o.getPrompt());
			totalLatency += result.latencyMs();
			totalFrames += frames.size();
			sceneCaptions.add(new SceneCaption(seq++, scene.getFrom(), scene.getTo(), result.text()));
		}

		// 3) Build the overall caption as a labelled timeline of the scene captions.
		StringBuilder sb = new StringBuilder();
		for (SceneCaption sc : sceneCaptions) {
			sb.append("Scene ").append(sc.seq() + 1)
				.append(" [frames ").append(sc.fromFrame()).append('-').append(sc.toFrame()).append("]: ")
				.append(sc.caption()).append('\n');
		}
		return new VideoCaptionOutput(sb.toString().trim(), sceneCaptions, totalLatency, totalFrames);
	}
}
