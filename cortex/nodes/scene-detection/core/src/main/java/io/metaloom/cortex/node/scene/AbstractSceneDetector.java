package io.metaloom.cortex.node.scene;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.opencv.core.Mat;
import io.metaloom.opencv.core.Size;
import io.metaloom.opencv.imgproc.Imgproc;

import io.metaloom.cortex.node.scene.detector.DetectionResult;
import io.metaloom.cortex.node.scene.detector.Detector;
import io.metaloom.cortex.node.scene.detector.SceneDetector;
import io.metaloom.cortex.media.scene.Scene;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.VideoFrame;
import io.metaloom.video4j.utils.SimpleImageViewer;

public abstract class AbstractSceneDetector implements SceneDetector {

	private static final Logger log = LoggerFactory.getLogger(AbstractSceneDetector.class);

	private final int videoChopRate = 10;
	protected final int VIDEO_SCALE_SIZE = 256;
	private SimpleImageViewer viewer;

	public AbstractSceneDetector() {
		if (!GraphicsEnvironment.isHeadless()) {
			viewer = new SimpleImageViewer();
		}
	}

	protected SceneDetectionResult detect(VideoFile video, Detector detector) {
		int showCut = 0;
		double shownDelta = 0;
		SceneDetectionResult result = new SceneDetectionResult();
		long nTotalFrame = 0;
		long start = System.currentTimeMillis();

		long videoLength = video.length();
		// Start frame of the scene currently being accumulated. A detected cut closes the scene [sceneStart, cutFrame) and opens a new one at cutFrame.
		long sceneStart = 0;
		// Debounce for the boundary detector: ignore a cut that would produce a scene shorter than this. It suppresses the (harmless) delta on the very
		// first sampled frame, collapses the 2-3 consecutive sampled frames a single hard cut typically spans into one boundary, and stops a transient
		// high-motion spike from shattering a scene. ~0.5s worth of frames, but never below two sampling steps.
		double fps = video.fps();
		long minSceneLength = Math.max(videoChopRate * 2L, fps > 0 ? Math.round(fps * 0.5d) : 0L);

		for (int nFrame = 0; nFrame < videoLength; nFrame += videoChopRate) {
			nTotalFrame++;
			if (nTotalFrame % 1000 == 0) {
				long dur = System.currentTimeMillis() - start;
				double avg = (double) nTotalFrame / (double) dur;
				log.debug("Frame rate: {} fps at frame {}/{}", String.format("%.2f", avg), nFrame, videoLength);
			}
			// Advance videoChopRate frames, keeping the last readable one as the sample. Stop cleanly at end-of-stream instead of relying on a fixed
			// tail margin (the old "length - 100" guard truncated short clips, so their final cuts were never examined). The frames we step over are
			// freed as we go - see free(VideoFrame).
			VideoFrame frame = null;
			for (int i = 0; i < videoChopRate; i++) {
				VideoFrame next = video.frame();
				if (next == null || next.mat() == null) {
					free(next);
					break;
				}
				free(frame);
				frame = next;
			}
			if (frame == null) {
				break;
			}

			try {
				Imgproc.resize(frame.mat(), frame.mat(), new Size(VIDEO_SCALE_SIZE, VIDEO_SCALE_SIZE), 0, 0, Imgproc.INTER_LINEAR);

				BufferedImage img = frame.toImage();
				DetectionResult frameResult = detector.test(img, frame);
				boolean isSplit = frameResult.delta() > frameResult.threshold();
				if (isSplit) {
					showCut = 1;
					// Record the scene boundary. Guard against over-segmentation: only close the scene if it has reached the minimum length, which also
					// filters the spurious first-frame delta.
					if (nFrame - sceneStart >= minSceneLength) {
						result.addScene(new Scene(sceneStart, nFrame));
						sceneStart = nFrame;
					}
				}

				if (viewer != null) {
					// int diff = (int) ((delta / 100) * 3);
					// System.out.println(nFrame + " " + diff + " " + String.format("%.2f", delta));
					// Color color = new Color(red, 0, 0);
					// g.setColor(color);
					// g.fillRect(10, 10, 10, 10);
					Graphics g = img.getGraphics();
					if (showCut != 0 && showCut < 8) {
						g.setColor(Color.GREEN);
						g.fillRect(10, 10, 50, 50);
						showCut++;
					} else {
						showCut = 0;
					}
					g.setColor(Color.WHITE);
					g.setFont(new Font("TimesRoman", Font.BOLD, 20));
					if (frameResult.delta() > frameResult.threshold() / 2) {
						shownDelta = frameResult.delta();
					}
					g.drawString(nFrame + " " + String.format("%.2f", frameResult.delta()) + " D: " + String.format("%.0f", shownDelta), 30,
						img.getHeight() - 20);
					viewer.show(img);
				}

			} catch (Exception e) {
				log.error("Scene detection failed at frame {} of {} — skipping frame", nFrame, video.length(), e);
				continue;
			} finally {
				free(frame);
			}

		}
		// Close the trailing scene that runs from the last cut (or the start, if no cut was found) to the end of the video. This also yields the
		// single whole-video scene when no cut was detected.
		if (videoLength > sceneStart) {
			result.addScene(new Scene(sceneStart, videoLength));
		} else if (result.scenes().isEmpty()) {
			// Degenerate case (empty/too-short video): still return one scene.
			result.addScene(new Scene(0, videoLength));
		}
		return result;
	}

	/**
	 * Free the native buffer behind a decoded frame.
	 *
	 * <p>
	 * {@link Mat} is a bare FFM handle with no cleaner: a frame that is not freed here keeps its pixel data for the lifetime of the JVM. The loop above
	 * decodes <em>every</em> frame of the video and samples only every {@code videoChopRate}-th one, so dropping the rest on the floor used to retain
	 * the whole decoded video - 28 GB of RSS for a single test class.
	 *
	 * <p>
	 * The consequence for implementors: a {@link Detector} must not hold on to the frame, or to its {@link Mat}, after
	 * {@link Detector#test(BufferedImage, VideoFrame)} returns. Anything it needs to compare against on the next call has to be an independent copy.
	 *
	 * @param frame
	 *            the frame to free, may be {@code null}
	 */
	private static void free(VideoFrame frame) {
		if (frame == null) {
			return;
		}
		Mat mat = frame.mat();
		if (mat != null) {
			// close() deletes the cv::Mat, whose destructor drops the pixel data; release() alone would leak the handle.
			mat.close();
		}
	}
}
