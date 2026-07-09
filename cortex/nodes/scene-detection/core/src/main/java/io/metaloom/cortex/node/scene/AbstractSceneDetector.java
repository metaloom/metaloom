package io.metaloom.cortex.node.scene;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GraphicsEnvironment;
import java.awt.image.BufferedImage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
		for (int nFrame = 0; nFrame < video.length() - 100; nFrame += videoChopRate) {
			nTotalFrame++;
			if (nTotalFrame % 1000 == 0) {
				long dur = System.currentTimeMillis() - start;
				double avg = (double) nTotalFrame / (double) dur;
				log.debug("Frame rate: {} fps at frame {}/{}", String.format("%.2f", avg), nFrame, video.length());
			}
			for (int i = 0; i < videoChopRate-1; i++) {
				video.frame();
			}
			// video.seekToFrame(nFrame);
			VideoFrame frame = video.frame();

			try {
				Imgproc.resize(frame.mat(), frame.mat(), new Size(VIDEO_SCALE_SIZE, VIDEO_SCALE_SIZE), 0, 0, Imgproc.INTER_LINEAR);

				BufferedImage img = frame.toImage();
				DetectionResult frameResult = detector.test(img, frame);
				boolean isSplit = frameResult.delta() > frameResult.threshold();
				if (isSplit) {
					showCut = 1;
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
			}

		}
		// No cut detection means one scene
		if (result.scenes().isEmpty()) {
			result.addScene(new Scene(0, video.length()));
		}
		return result;
	}
}
