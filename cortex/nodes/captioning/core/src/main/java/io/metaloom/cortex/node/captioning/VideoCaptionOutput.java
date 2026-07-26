package io.metaloom.cortex.node.captioning;

import java.util.List;

/**
 * The full result of captioning one video: the overall caption text, an optional per-scene breakdown (populated only by the scene strategy), and the
 * accumulated model latency in milliseconds. Consumed by {@code compute()} for persistence and by the comparison harness for scoring.
 */
public record VideoCaptionOutput(String caption, List<SceneCaption> scenes, long modelLatencyMs, int frameCount) {

	/** One captioned scene: its sequence index, its {@code [fromFrame, toFrame]} range, and the caption produced for it. */
	public record SceneCaption(int seq, long fromFrame, long toFrame, String caption) {
	}
}
