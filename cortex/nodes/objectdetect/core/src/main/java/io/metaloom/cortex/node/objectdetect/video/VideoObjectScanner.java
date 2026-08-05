package io.metaloom.cortex.node.objectdetect.video;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.node.objectdetect.DetectedObject;
import io.metaloom.cortex.node.objectdetect.DetectionFilter;
import io.metaloom.cortex.node.objectdetect.ObjectDetection;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNodeOptions;
import io.metaloom.cortex.node.objectdetect.ObjectDetector;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.VideoFrame;
import io.metaloom.video4j.opencv.CVUtils;

/**
 * Samples a video and runs object detection on the frames it keeps.
 *
 * <p>
 * Deliberately not {@code VideoFaceScanner}: that one splits the file into windows, scores every
 * crop for blur and keeps the ten sharpest faces, all of which is about picking one good picture of
 * a person. Object detection wants the opposite — an even sample across the whole file, so that a
 * car appearing only in the last minute is still found.
 * </p>
 *
 * <p>
 * Frames are read <strong>sequentially</strong> and the ones between samples are decoded and
 * dropped, rather than seeking to each sample. Seeking a long-GOP file has to decode from the
 * preceding keyframe anyway, and lands on a different frame than it was asked for often enough that
 * the reported frame numbers would stop matching the file.
 * </p>
 */
public class VideoObjectScanner {

	private static final Logger log = LoggerFactory.getLogger(VideoObjectScanner.class);

	private final ObjectDetector detector;

	@Inject
	public VideoObjectScanner(ObjectDetector detector) {
		this.detector = detector;
	}

	/**
	 * Scan the video, keeping detections that pass the configured confidence and class filters.
	 *
	 * @param video   an opened video; not closed here, the caller owns it
	 * @param options the node options supplying chop rate, scale, filters and the cap
	 * @return what was found, and whether the cap cut it short
	 */
	public ObjectScanReport scan(VideoFile video, ObjectDetectNodeOptions options) {
		int chopRate = Math.max(1, options.getVideoChopRate());
		int maxDetections = options.getMaxDetections();
		long length = video.length();

		// Only ever downscale. Enlarging a frame to reach videoScaleSize costs inference time on
		// interpolated pixels and finds nothing that was not already there.
		int nativeWidth = video.width();
		int nativeHeight = video.height();
		boolean downscale = Math.max(nativeWidth, nativeHeight) > options.getVideoScaleSize();

		List<DetectedObject> detections = new ArrayList<>();
		int framesScanned = 0;
		boolean capped = false;

		for (long frameNumber = 0; frameNumber < length && !capped; frameNumber += chopRate) {
			VideoFrame sample = nextSample(video, chopRate);
			if (sample == null) {
				break;
			}
			try {
				double scale = 1.0d;
				if (downscale) {
					CVUtils.resize(sample, options.getVideoScaleSize());
					// The resize keeps the aspect ratio, so one factor covers both axes. Derived from the
					// frame we are about to hand over rather than from the requested size, because the
					// rounding in CVUtils means the two are not always the same number.
					int resizedWidth = sample.mat().width();
					if (resizedWidth > 0) {
						scale = (double) nativeWidth / (double) resizedWidth;
					}
				}

				framesScanned++;
				int frameIndex = (int) sample.number();
				for (ObjectDetection detection : detector.detect(sample.mat())) {
					DetectedObject kept = DetectionFilter.accept(detection, frameIndex, scale, options);
					if (kept == null) {
						continue;
					}
					detections.add(kept);
					if (detections.size() >= maxDetections) {
						log.info("Reached the {} detection cap for {} after {} frames", maxDetections, video.path(), framesScanned);
						capped = true;
						break;
					}
				}
			} finally {
				close(sample);
			}
		}

		return new ObjectScanReport(detections, framesScanned, capped);
	}

	/**
	 * Advance {@code chopRate} frames and return the last readable one.
	 *
	 * <p>
	 * Every frame this walks past is closed. {@link VideoFrame} owns a {@code Mat}, and a scan of a
	 * feature-length file walks past six figures of them.
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

	/**
	 * Release a frame without letting the cleanup fail the scan.
	 */
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
