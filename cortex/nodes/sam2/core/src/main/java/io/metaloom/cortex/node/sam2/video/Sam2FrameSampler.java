package io.metaloom.cortex.node.sam2.video;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.node.sam2.Sam2Images;
import io.metaloom.cortex.node.sam2.Sam2NodeOptions;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.VideoFrame;
import io.metaloom.video4j.Videos;

/**
 * Samples a video into the base64 JPEG sequence the SAM 2 sidecar's {@code /v1/track} takes.
 *
 * <p>
 * Frames are read <strong>sequentially</strong> and the ones between samples are decoded and
 * dropped, rather than seeking to each sample — the policy {@code VideoObjectScanner} documents.
 * Seeking a long-GOP file has to decode from the preceding keyframe anyway, and lands on a different
 * frame than it was asked for often enough that the reported frame numbers would stop matching the
 * file. Since those numbers are the only handle a consumer has on a tracked mask, that would be worse
 * than slow.
 * </p>
 *
 * <p>
 * Sampling happens here rather than in Python for three reasons: the tree already owns this policy
 * three times over (thumbnail, scene-detection, objectdetect), {@code videoChopRate} is already a
 * node option, and a server-side path would silently require the sidecar to be co-located with the
 * worker. Sending the video bytes instead is not bounded — a feature film is gigabytes, and base64
 * adds a third.
 * </p>
 */
public class Sam2FrameSampler {

	private static final Logger log = LoggerFactory.getLogger(Sam2FrameSampler.class);

	@Inject
	public Sam2FrameSampler() {
	}

	/**
	 * Open the video, walk it, and encode every {@code videoChopRate}-th frame up to
	 * {@code maxFrames}.
	 *
	 * <p>
	 * Opening happens here rather than in the node so that video4j — and therefore the OpenCV native
	 * runtime — is reachable only through this one seam. A test that stubs this class needs no natives
	 * at all, which is what keeps the node's own tests runnable anywhere.
	 * </p>
	 *
	 * @param path    absolute path of the video to sample
	 * @param options supplies the chop rate, the frame cap and the dimension cap
	 * @return the encoded frames and their source numbers
	 * @throws IOException when the video cannot be read or a frame cannot be encoded as JPEG
	 */
	public SampledFrames sample(String path, Sam2NodeOptions options) throws IOException {
		try (VideoFile video = Videos.open(path)) {
			return sample(video, options);
		}
	}

	/**
	 * Walk an already-open video. Package-visible so the sampler's own test can drive a fake
	 * {@link VideoFile} without going through the natives.
	 *
	 * @param video   an opened video; not closed here, the caller owns it
	 * @param options supplies the chop rate, the frame cap and the dimension cap
	 * @return the encoded frames and their source numbers
	 * @throws IOException when a frame cannot be encoded as JPEG
	 */
	SampledFrames sample(VideoFile video, Sam2NodeOptions options) throws IOException {
		int chopRate = Math.max(1, options.getVideoChopRate());
		int maxFrames = Math.max(1, options.getMaxFrames());
		long length = video.length();

		int nativeWidth = video.width();
		int nativeHeight = video.height();

		List<String> encoded = new ArrayList<>();
		List<Integer> frameNumbers = new ArrayList<>();
		int sampledWidth = nativeWidth;
		int sampledHeight = nativeHeight;
		boolean capped = false;

		for (long frameNumber = 0; frameNumber < length; frameNumber += chopRate) {
			if (encoded.size() >= maxFrames) {
				// Not an error: a long clip simply has more frames than one propagation can hold, and
				// the caller reports CAPPED so nobody reads the result as "the whole file".
				log.info("Reached the {} frame cap for {} after {} samples", maxFrames, video.path(), encoded.size());
				capped = true;
				break;
			}
			VideoFrame sample = nextSample(video, chopRate);
			if (sample == null) {
				break;
			}
			try {
				BufferedImage image = Sam2Images.downscale(sample.toImage(), options.getMaxDim());
				sampledWidth = image.getWidth();
				sampledHeight = image.getHeight();
				encoded.add(Sam2Images.toBase64Jpeg(image));
				frameNumbers.add((int) sample.number());
			} finally {
				close(sample);
			}
		}

		return new SampledFrames(List.copyOf(encoded), List.copyOf(frameNumbers),
			nativeWidth, nativeHeight, sampledWidth, sampledHeight, capped);
	}

	/**
	 * Advance {@code chopRate} frames and return the last readable one.
	 *
	 * <p>
	 * Every frame this walks past is closed. {@link VideoFrame} owns a {@code Mat}, and a walk over a
	 * feature-length file passes six figures of them.
	 * </p>
	 *
	 * @return the sample frame, or null at end of stream
	 */
	private VideoFrame nextSample(VideoFile video, int chopRate) {
		VideoFrame sample = null;
		for (int i = 0; i < chopRate; i++) {
			VideoFrame next = video.frame();
			if (next == null || next.mat() == null) {
				close(next);
				break;
			}
			close(sample);
			sample = next;
		}
		return sample;
	}

	/** Release a frame without letting the cleanup fail the walk. */
	private static void close(VideoFrame frame) {
		if (frame == null) {
			return;
		}
		try {
			frame.close();
		} catch (Exception e) {
			log.debug("Failed to release a video frame", e);
		}
	}
}
