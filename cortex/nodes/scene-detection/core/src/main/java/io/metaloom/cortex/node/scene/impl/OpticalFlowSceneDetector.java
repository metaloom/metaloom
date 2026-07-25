package io.metaloom.cortex.node.scene.impl;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.metaloom.opencv.core.Core;
import io.metaloom.opencv.core.Mat;
import io.metaloom.opencv.core.MatOfPoint;
import io.metaloom.opencv.core.MatOfPoint2f;
import io.metaloom.opencv.core.Point;
import io.metaloom.opencv.imgproc.Imgproc;
import io.metaloom.opencv.video.SparsePyrLKOpticalFlow;

import io.metaloom.cortex.node.scene.AbstractSceneDetector;
import io.metaloom.cortex.node.scene.detector.DetectionResult;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.impl.MatProvider;
import io.metaloom.video4j.opencv.CVUtils;
import io.metaloom.video4j.utils.ImageUtils;

/**
 * Scene-cut detector. Each sampled frame is Canny-enhanced and converted to grayscale, then compared to the previous sampled frame; the normalized mean
 * absolute pixel difference is the cut signal (≈0.04–0.08 within a shot, ≈0.5 across a hard cut), and {@link AbstractSceneDetector} turns a threshold
 * crossing into a scene boundary.
 *
 * <p>Frame difference (not sparse optical flow) drives the decision on purpose: the previous Lucas-Kanade point-tracking approach produced no usable signal
 * here — the FFM {@code SparsePyrLKOpticalFlow.calc} binding returned a degenerate one-row status, so the miss ratio was always 0 and no cut was ever
 * detected. The optical-flow point overlay is still drawn, but only for the interactive viewer (non-headless), so it adds no cost to server / test runs.
 */
public class OpticalFlowSceneDetector extends AbstractSceneDetector {

	/** Cut when the normalized mean frame difference exceeds this. Tuned to the Canny-enhanced signal above; low enough to catch hard cuts, high enough to
	 * ignore ordinary intra-shot motion. */
	private final double FRAME_DIFF_THRESHOLD = 0.25d;

	private final int MAX_CORNERS = 600;
	private final double qualityLevel = 0.05f;
	private final double minDistance = 10f;
	private final int blockSize = 15;
	private final int gradientSize = 3;
	private final boolean useHarrisDetector = false;
	private final double k = 0.04f;

	@Override
	public SceneDetectionResult detect(VideoFile video) {
		AtomicReference<Mat> previousGray = new AtomicReference<>();
		boolean headless = GraphicsEnvironment.isHeadless();
		Mat err = MatProvider.mat();
		return detect(video, (img, frame) -> {
			Mat colorFrame = frame.mat();
			Mat grayFrame = CVUtils.toGrayScale(colorFrame);

			// Canny-enhance so structural (edge) changes dominate the difference over lighting/exposure drift.
			Mat cannyFrame = MatProvider.mat();
			Imgproc.Canny(grayFrame, cannyFrame, 50f, 70f);
			Imgproc.dilate(cannyFrame, cannyFrame, MatProvider.mat());
			Core.addWeighted(cannyFrame, 0.50f, grayFrame, 1.f, 1.0f, grayFrame);
			cannyFrame.release();

			// Cut signal: normalized mean absolute difference against the previous sampled frame.
			Mat prev = previousGray.get();
			double delta = 0d;
			if (prev != null) {
				Mat diff = MatProvider.mat();
				Core.absdiff(prev, grayFrame, diff);
				delta = Core.mean(diff).val[0] / 255d;
				diff.release();
			}

			// Optional optical-flow point overlay for the interactive viewer only.
			if (!headless) {
				Graphics g = img.getGraphics();
				g.drawImage(ImageUtils.matToBufferedImage(grayFrame), 1, 1, null);
				drawFlowOverlay(g, prev, grayFrame, err);
			}

			// Store an independent copy as the reference for the next frame and release the old one (video4j recycles the live Mat, so the reference must
			// be cloned — otherwise the comparison degenerates to a frame against itself).
			Mat old = previousGray.getAndSet(grayFrame.clone());
			if (old != null) {
				old.release();
			}
			return new DetectionResult(delta, FRAME_DIFF_THRESHOLD);
		});
	}

	/** Draw the Lucas-Kanade tracked points onto the viewer image. Visualization only — not part of the cut decision. */
	private void drawFlowOverlay(Graphics g, Mat previousGray, Mat grayFrame, Mat err) {
		if (previousGray == null) {
			return;
		}
		try {
			SparsePyrLKOpticalFlow flow = SparsePyrLKOpticalFlow.create();
			MatOfPoint corners = new MatOfPoint();
			Imgproc.goodFeaturesToTrack(grayFrame, corners, MAX_CORNERS, qualityLevel, minDistance, new Mat(), blockSize, gradientSize,
				useHarrisDetector, k);
			MatOfPoint2f p0 = new MatOfPoint2f(corners.toArray());
			MatOfPoint2f p1 = new MatOfPoint2f();
			MatOfPoint2f status = new MatOfPoint2f();
			flow.calc(previousGray, grayFrame, p0, p1, status, err);
			List<Point> points = new ArrayList<>();
			for (Point p : p1.toArray()) {
				points.add(p);
			}
			g.setColor(Color.RED);
			for (Point p : points) {
				g.drawOval((int) p.x, (int) p.y, 4, 4);
			}
		} catch (Exception e) {
			// Overlay is best-effort; never let visualization break detection.
		}
	}
}
