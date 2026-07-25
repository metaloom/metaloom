package io.metaloom.cortex.node.videocaptioning;

import java.io.File;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.video4j.VideoFile;

/**
 * Variant C - native video. Hands the whole video file to the model via a {@code video_url} part and lets the server do its own temporal frame sampling
 * (Qwen2.5-VL's native video path). Only vLLM decodes this; llama.cpp does not, so this variant is expected to fail there.
 *
 * <p>The node still opens the file through video4j (so the base-class lifecycle and existence checks hold) but does not decode frames itself - it only needs
 * the absolute path to build a {@code file://} URI.
 */
public class NativeVideoCaptioningNode extends AbstractVideoCaptioningNode {

	@Inject
	public NativeVideoCaptioningNode(@Nullable LoomClient client, CortexOptions cortexOptions, VideoCaptioningNodeOptions options, VideoVLMClient vlm) {
		super(client, cortexOptions, options, vlm);
	}

	@Override
	public String name() {
		return "video-captioning-native";
	}

	@Override
	protected VideoCaptionOutput doCaption(VideoFile video) throws Exception {
		String fileUri = new File(video.path()).getAbsoluteFile().toURI().toString();
		CaptionResult result = vlm.captionVideoUrl(fileUri, options().getPrompt());
		return new VideoCaptionOutput(result.text(), List.of(), result.latencyMs(), 1);
	}
}
