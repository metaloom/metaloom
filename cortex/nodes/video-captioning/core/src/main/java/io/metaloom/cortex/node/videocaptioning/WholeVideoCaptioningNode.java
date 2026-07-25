package io.metaloom.cortex.node.videocaptioning;

import java.awt.image.BufferedImage;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.video4j.VideoFile;

/**
 * Variant A - whole-video single caption. Samples {@code frameCount} frames evenly across the entire clip and sends them as one multi-image prompt, yielding
 * a single description. Works on every OpenAI-compatible backend (llama.cpp and vLLM). No scene segmentation, no timestamps.
 */
public class WholeVideoCaptioningNode extends AbstractVideoCaptioningNode {

	@Inject
	public WholeVideoCaptioningNode(@Nullable LoomClient client, CortexOptions cortexOptions, VideoCaptioningNodeOptions options, VideoVLMClient vlm) {
		super(client, cortexOptions, options, vlm);
	}

	@Override
	public String name() {
		return "video-captioning-whole";
	}

	@Override
	protected VideoCaptionOutput doCaption(VideoFile video) throws Exception {
		VideoCaptioningNodeOptions o = options();
		List<BufferedImage> frames = FrameSampler.sampleEvenly(video, o.getFrameCount(), o.getTargetFrameSize());
		CaptionResult result = vlm.captionFrames(frames, o.getPrompt());
		return new VideoCaptionOutput(result.text(), List.of(), result.latencyMs(), frames.size());
	}
}
