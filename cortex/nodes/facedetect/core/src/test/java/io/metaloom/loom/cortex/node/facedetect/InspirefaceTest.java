package io.metaloom.loom.cortex.node.facedetect;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.inspireface4j.BoundingBox;
import io.metaloom.inspireface4j.Detection;
import io.metaloom.inspireface4j.FaceAttributes;
import io.metaloom.inspireface4j.InspirefaceLib;
import io.metaloom.inspireface4j.InspirefaceSession;
import io.metaloom.inspireface4j.SessionFeature;
import io.metaloom.inspireface4j.data.FaceDetections;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.VideoFrame;
import io.metaloom.video4j.opencv.CVUtils;

/**
 * Worked example of driving inspireface4j over a video with video4j.
 *
 * <p>
 * The pack path is resolved relative to the module, like every other test here, rather than
 * from a developer's home directory, and the video comes from the shared test media instead
 * of {@code /extra/vid}. The example also no longer opens a Swing viewer per frame, so it can
 * run unattended.
 * </p>
 */
public class InspirefaceTest extends AbstractFacedetectMediaTest {

	private static final String DEFAULT_PACK = "packs/Pikachu";

	/** Keeps the example bounded — it demonstrates the API, it does not need the whole video. */
	private static final int MAX_FRAMES = 25;

	@Test
	public void testVideoUsageExample() throws Exception {
		// SNIPPET START video-usage.example

		// Initialize video4j and InspirefaceLib (Video4j is used to handle OpenCV Mat)
		Video4j.init();

		int detectedFaces = 0;

		// The features have to be requested up front: a session opened without them still detects
		// faces, but embedding() and attributes() come back empty.
		try (InspirefaceSession session = InspirefaceLib.session(DEFAULT_PACK, 640,
			SessionFeature.ENABLE_FACE_RECOGNITION, SessionFeature.ENABLE_FACE_ATTRIBUTE, SessionFeature.ENABLE_FACE_POSE)) {

			// Open the video using Video4j
			try (VideoFile video = VideoFile.open(video2().path().toString())) {

				// Process each frame
				VideoFrame frame;
				int frameCount = 0;
				// A frame owns a native buffer that no cleaner will ever reclaim, so it is closed each
				// time round - a sampling loop that does not is a leak the size of the video.
				while ((frame = video.frame()) != null && frameCount++ < MAX_FRAMES) {
					try (VideoFrame current = frame) {

						// Optionally downscale the frame
						CVUtils.resize(current, 512);

						// Run the detection on the mat reference
						FaceDetections detections = session.detect(current.mat(), true);

						if (!detections.isEmpty()) {
							// Extract the face embedding from the first face
							float[] embedding = session.embedding(current.mat(), detections, 0);
							// Extract the face attributes
							List<FaceAttributes> attrs = session.attributes(current.mat(), detections, true);
							assertThat(embedding).as("A detected face must yield an embedding").isNotEmpty();
							assertThat(attrs).as("A detected face must yield attributes").isNotEmpty();
						}

						// Inspect the detections
						for (Detection detection : detections) {
							double confidence = detection.conf();
							BoundingBox box = detection.box();
							assertThat(confidence).as("Detection confidence is a probability").isBetween(0d, 1d);
							assertThat(box).as("Every detection carries a bounding box").isNotNull();
							detectedFaces++;
						}
					}
				}
			}
		}
		// SNIPPET END video-usage.example

		assertThat(detectedFaces).as("The test video contains faces, so the example must detect some").isPositive();
	}

}
