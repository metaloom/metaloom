package io.metaloom.loom.cortex.node.facedetect;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.facedetect.FacedetectNodeModule;
import io.metaloom.cortex.node.facedetect.FacedetectNodeOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScannerReport;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;

/**
 * Scans a test video for faces and checks the report the scanner produces.
 *
 * <p>
 * This used to be an interactive developer scratchpad: it opened a hard-coded video from
 * {@code /extra/vid}, rendered every hit through a Swing viewer and then blocked on
 * {@code System.in.read()}. That cannot run unattended — once the missing video no longer
 * failed it first, it would simply hang. It now runs against the shared test media and
 * asserts the report instead of displaying it.
 * </p>
 */
public class VideoFaceScannerTest extends AbstractFacedetectMediaTest {

	private static final String DEFAULT_PACK = "packs/Pikachu";

	private static final int WINDOW_COUNT = 100;

	static {
		Video4j.init();
	}

	@Test
	public void testScanReportsFaces() throws InterruptedException, IOException, URISyntaxException {
		VideoFaceScanner detector = scanner();

		try (VideoFile video = Videos.open(video2().path())) {
			VideoFaceScannerReport report = detector.scan(video, WINDOW_COUNT);

			assertThat(report).as("The scanner must return a report").isNotNull();
			assertThat(report.getFaces()).as("The test video contains faces, so the scan must find some").isNotEmpty();

			for (Face face : report.getFaces()) {
				assertThat(face.box()).as("Every detected face carries a bounding box").isNotNull();
				// Deliberately not asserting an embedding: the scanner does not attach one. They came
				// from a remote InsightFace service whose call is commented out, and FacedetectNode
				// consumes only the box and frame index.
				// Bind first: Face#get is generic, so passing it straight to assertThat leaves the
				// compiler choosing between the IntPredicate and Predicate<T> overloads.
				Object frameRef = face.get("frame");
				assertThat(frameRef).as("Every face records the frame it was found in").isNotNull();
			}
		}
	}

	private VideoFaceScanner scanner() {
		FacedetectNodeOptions options = new FacedetectNodeOptions();
		options.setInspirefacePackPath(DEFAULT_PACK);
		options.setMinFaceHeightFactor(0.05f).setVideoScaleSize(512);
		InspireFacedetector inspireface = FacedetectNodeModule.inspirefaceDetector(options);
		return new VideoFaceScanner(inspireface);
	}

}
