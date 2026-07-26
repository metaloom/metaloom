package io.metaloom.cortex.node.captioning;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import io.metaloom.cortex.media.scene.Scene;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.cortex.node.captioning.VideoCaptionOutput.SceneCaption;
import io.metaloom.cortex.node.scene.impl.OpticalFlowSceneDetector;
import io.metaloom.video4j.VideoFile;

/**
 * Turns an opened video into a caption using the configured {@link VideoCaptioningStrategy}. This carries the three interchangeable video-captioning
 * strategies that used to live in separate {@code video-captioning-*} nodes - they are now variants of the single {@code captioning} node, selected by
 * {@link CaptioningNodeOptions#getVideoStrategy()}. Frame decoding is handled by {@link FrameSampler} and every strategy drives the same OpenAI-compatible
 * {@link VideoVLMClient}.
 */
public class VideoCaptioner {

	private final CaptioningNodeOptions options;
	private final VideoVLMClient vlm;
	private final OpticalFlowSceneDetector detector = new OpticalFlowSceneDetector();

	public VideoCaptioner(CaptioningNodeOptions options, VideoVLMClient vlm) {
		this.options = options;
		this.vlm = vlm;
	}

	/** Caption the opened video using the configured strategy. */
	public VideoCaptionOutput caption(VideoFile video) throws Exception {
		return switch (options.getVideoStrategy()) {
			case WHOLE -> captionWhole(video);
			case SCENE -> captionScene(video);
			case NATIVE -> captionNative(video);
		};
	}

	/**
	 * Whole-video single caption. Samples {@code frameCount} frames evenly across the entire clip and sends them as one multi-image prompt, yielding a
	 * single description. Works on every OpenAI-compatible backend (llama.cpp and vLLM). No scene segmentation, no timestamps.
	 */
	private VideoCaptionOutput captionWhole(VideoFile video) throws Exception {
		List<BufferedImage> frames = FrameSampler.sampleEvenly(video, options.getFrameCount(), options.getTargetFrameSize());
		CaptionResult result = vlm.captionFrames(frames, options.getVideoPrompt());
		return new VideoCaptionOutput(result.text(), List.of(), result.latencyMs(), frames.size());
	}

	/**
	 * Scene-first. A dedicated scene-segmentation model (optical-flow {@link OpticalFlowSceneDetector}) cuts the video into shots up front, then the VLM
	 * captions each shot independently. This yields a per-scene caption timeline instead of one whole-video description. The overall {@code caption} is the
	 * labelled concatenation of the per-scene captions; the structured per-scene breakdown is carried in {@link VideoCaptionOutput#scenes()}.
	 */
	private VideoCaptionOutput captionScene(VideoFile video) throws Exception {
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
		int limit = Math.min(scenes.size(), options.getMaxScenes());
		int perScene = Math.max(2, Math.min(options.getFrameCount(), 4));
		for (int i = 0; i < limit; i++) {
			Scene scene = scenes.get(i);
			List<BufferedImage> frames = FrameSampler.sampleRange(video, scene.getFrom(), scene.getTo(), perScene, options.getTargetFrameSize());
			if (frames.isEmpty()) {
				continue;
			}
			CaptionResult result = vlm.captionFrames(frames, options.getVideoPrompt());
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

	/**
	 * Native video. Hands the whole video file to the model via a {@code video_url} part and lets the server do its own temporal frame sampling
	 * (Qwen2.5-VL's native video path). Only vLLM decodes this; llama.cpp does not, so this strategy is expected to fail there.
	 */
	private VideoCaptionOutput captionNative(VideoFile video) throws Exception {
		String fileUri = new File(video.path()).getAbsoluteFile().toURI().toString();
		CaptionResult result = vlm.captionVideoUrl(fileUri, options.getVideoPrompt());
		return new VideoCaptionOutput(result.text(), List.of(), result.latencyMs(), 1);
	}
}
