package io.metaloom.cortex.node.scene.impl;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.metaloom.opencv.core.Core;
import io.metaloom.opencv.core.Mat;
import io.metaloom.opencv.core.MatOfFloat;
import io.metaloom.opencv.core.MatOfInt;
import io.metaloom.opencv.imgproc.Imgproc;

import io.metaloom.cortex.node.scene.AbstractSceneDetector;
import io.metaloom.cortex.node.scene.detector.DetectionResult;
import io.metaloom.cortex.media.scene.SceneDetectionResult;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.impl.MatProvider;
import io.metaloom.video4j.opencv.CVUtils;

public class HistogramSceneDetector extends AbstractSceneDetector {

	@Override
	public SceneDetectionResult detect(VideoFile video) {

		final double HIST_THRESHOLD = 0.2f;
		// The reference for the next comparison is an edge-enhanced Mat we own outright, not the frame: the base loop frees the frame as soon as the
		// callback returns.
		AtomicReference<Mat> previous = new AtomicReference<>();
		try {
			return detect(video, (img, frame) -> {
				Mat colorFrame = frame.mat();
				Mat enhanced = MatProvider.mat();
				try (Mat cannyGray = CVUtils.canny(colorFrame, 30f, 30f); Mat cannyFrame = CVUtils.toBGR(cannyGray)) {
					Core.addWeighted(cannyFrame, 0.6f, colorFrame, 0.2f, 1f, enhanced);
				}

				Mat prev = previous.getAndSet(enhanced);
				// Nothing to compare the very first sampled frame against, so it can never be a cut.
				double delta = prev == null ? 0d : diff(enhanced, prev);
				if (prev != null) {
					prev.close();
				}
				return new DetectionResult(delta, HIST_THRESHOLD);
			});
		} finally {
			Mat last = previous.getAndSet(null);
			if (last != null) {
				last.close();
			}
		}

	}

	private double diff(Mat frame, Mat previousFrame) {
		// Mat hsvFrameA = MatProvider.mat();
		// Mat hsvFrameB = MatProvider.mat();
		// Imgproc.cvtColor(previousFrame.mat(), hsvFrameA, Imgproc.COLOR_BGR2HSV);
		// Imgproc.cvtColor(frame.mat(), hsvFrameB, Imgproc.COLOR_BGR2HSV);

		int hBins = 50, sBins = 60;
		int[] histSize = { hBins, sBins };

		float[] ranges = { 0, 256, 0, 256 };
		// int channels = 3;
		int[] channels = { 0, 1 };

		// Imgproc.calcHist(Arrays.asList(frame.mat()), 3, new MatOfInt(3), new Mat(), null, new MatOfInt(buckets), new MatOfFloat(ranges), false);
		// double baseBase = Imgproc.compareHist( histBase, histBase, compareMethod );
		try (Mat histFrameA = calcHistogram(frame, channels, histSize, ranges);
			Mat histFrameB = calcHistogram(previousFrame, channels, histSize, ranges)) {
			return Imgproc.compareHist(histFrameA, histFrameB, 1);
			// System.out.println("hist: " + String.format("%.2f", histCompareResult));
		}

	}

	private Mat calcHistogram(Mat mat, int[] channels, int[] histSize, float[] ranges) {
		Mat hist = MatProvider.mat();
		List<Mat> hsvBaseList = Arrays.asList(mat);
		try (MatOfInt channelMat = new MatOfInt(channels);
			Mat mask = new Mat();
			MatOfInt histSizeMat = new MatOfInt(histSize);
			MatOfFloat rangeMat = new MatOfFloat(ranges)) {
			Imgproc.calcHist(hsvBaseList, channelMat, mask, hist, histSizeMat, rangeMat, false);
		}
		Core.normalize(hist, hist, 0, 1, Core.NORM_MINMAX);
		return hist;
	}

}
