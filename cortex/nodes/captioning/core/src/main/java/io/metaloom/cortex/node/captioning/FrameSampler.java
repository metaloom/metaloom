package io.metaloom.cortex.node.captioning;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.imgscalr.Scalr;

import io.metaloom.video4j.VideoFile;

/**
 * Extracts a small, evenly-spaced set of frames from a video for captioning. Sampling is done with {@link VideoFile#seekToFrame(long)} (the same idiom the
 * facedetect video scanner uses) and each frame is downscaled with imgscalr so the base64 payload sent to the vision model stays small.
 */
public final class FrameSampler {

	private FrameSampler() {
	}

	/**
	 * Sample {@code count} frames evenly across the whole video, each scaled so its longest edge is {@code targetSize}.
	 */
	public static List<BufferedImage> sampleEvenly(VideoFile video, int count, int targetSize) {
		return sampleRange(video, 0, Math.max(0, video.length() - 1), count, targetSize);
	}

	/**
	 * Sample {@code count} frames evenly across the frame range {@code [fromFrame, toFrame]} (inclusive). Used by the scene strategy to grab representative
	 * frames from a single detected scene.
	 */
	public static List<BufferedImage> sampleRange(VideoFile video, long fromFrame, long toFrame, int count, int targetSize) {
		List<BufferedImage> frames = new ArrayList<>();
		long span = Math.max(0, toFrame - fromFrame);
		int n = Math.max(1, count);
		for (int i = 0; i < n; i++) {
			// Place samples at the centre of n equal buckets so we avoid the very first/last (often black) frames.
			long frameNo = fromFrame + (span == 0 ? 0 : Math.round(((i + 0.5) / n) * span));
			video.seekToFrame(frameNo);
			BufferedImage img = video.frameToImage();
			if (img == null) {
				continue;
			}
			if (targetSize > 0 && Math.max(img.getWidth(), img.getHeight()) > targetSize) {
				img = Scalr.resize(img, targetSize);
			}
			frames.add(img);
		}
		return frames;
	}
}
