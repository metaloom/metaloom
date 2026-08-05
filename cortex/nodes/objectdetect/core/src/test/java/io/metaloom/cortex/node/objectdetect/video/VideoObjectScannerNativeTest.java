package io.metaloom.cortex.node.objectdetect.video;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.cortex.node.objectdetect.DetectedObject;
import io.metaloom.cortex.node.objectdetect.ObjectDetectNodeOptions;
import io.metaloom.cortex.node.objectdetect.ObjectDetector;
import io.metaloom.cortex.node.objectdetect.YoloObjectDetector;
import io.metaloom.video4j.Video4j;
import io.metaloom.video4j.VideoFile;
import io.metaloom.video4j.Videos;

/**
 * The video sampling loop, against a real decoder and a real model.
 *
 * <p>
 * This is the path the node exists for — per-frame inference over a whole file — and the one that
 * cannot be covered with a stub, because what is worth checking is precisely the parts a stub
 * replaces: that seeking a chop rate through the file terminates, that the boxes come back in
 * <em>native</em> frame coordinates after the downscale, and that the cap stops the scan.
 * </p>
 *
 * <pre>
 * mvn -o -pl cortex/nodes/objectdetect/core test -Dtest=VideoObjectScannerNativeTest \
 *     -Dobjectdetect.model=/path/to/YOLOv11n_voc.onnx \
 *     -Dobjectdetect.labels=/path/to/voc.names \
 *     -Dobjectdetect.video=/path/to/clip.mp4
 * </pre>
 */
@EnabledIfSystemProperty(named = "objectdetect.video", matches = ".+")
class VideoObjectScannerNativeTest {

	private static final String MODEL = System.getProperty("objectdetect.model");
	private static final String LABELS = System.getProperty("objectdetect.labels");
	private static final String VIDEO = System.getProperty("objectdetect.video");

	@BeforeAll
	static void initVideo4j() {
		Video4j.init();
	}

	private static VideoObjectScanner scanner() {
		ObjectDetector detector = new YoloObjectDetector(MODEL, LABELS, false, System.getProperty("yolo4j.onnxruntime.lib"));
		return new VideoObjectScanner(detector);
	}

	/** A coarse chop rate, so the test is a wiring check rather than a full transcode. */
	private static ObjectDetectNodeOptions options() {
		return new ObjectDetectNodeOptions().setVideoChopRate(150).setVideoScaleSize(640);
	}

	@Test
	void testScansAVideoAndReportsNativeFrameCoordinates() {
		try (VideoFile video = Videos.open(VIDEO)) {
			ObjectScanReport report = scanner().scan(video, options());

			assertNotNull(report);
			assertTrue(report.framesScanned() > 0, "the scan ran inference on no frames at all");
			assertFalse(report.detections().isEmpty(), "expected the model to find something in " + VIDEO);

			int width = video.width();
			int height = video.height();
			assertTrue(width > 0 && height > 0);

			for (DetectedObject detection : report.detections()) {
				BoundingBox box = detection.box();
				assertTrue(box.width() > 0 && box.height() > 0, "degenerate box: " + box);
				// The boxes were measured on a 640px copy and scaled back. Left unscaled they would all sit
				// in the top-left third of the frame - which still "looks like" boxes, and is wrong.
				assertTrue(box.x() >= 0 && box.x() < width, "x " + box.x() + " outside a " + width + "px frame");
				assertTrue(box.y() >= 0 && box.y() < height, "y " + box.y() + " outside a " + height + "px frame");
				assertTrue(detection.frameIndex() >= 0);
			}

			// Frame indices ascend: they are the durable detection rows' natural key alongside the ordinal,
			// and a scan that reported them out of order would upsert rows on top of each other.
			List<Integer> frames = report.detections().stream().map(DetectedObject::frameIndex).toList();
			for (int i = 1; i < frames.size(); i++) {
				assertTrue(frames.get(i) >= frames.get(i - 1), "frame indices went backwards: " + frames);
			}
		}
	}

	@Test
	void testTheCapStopsTheScanAndSaysSo() {
		try (VideoFile video = Videos.open(VIDEO)) {
			ObjectScanReport report = scanner().scan(video, options().setMaxDetections(3));

			assertEquals(3, report.detections().size());
			assertTrue(report.capped(), "a truncated scan must report that it was truncated");
		}
	}

	@Test
	void testAClassFilterKeepsOnlyWhatItNames() {
		String present;
		// A fresh handle per scan: scan() consumes the file to the end, so a second scan over the same
		// VideoFile starts at EOF and reports nothing at all.
		try (VideoFile video = Videos.open(VIDEO)) {
			present = scanner().scan(video, options()).detections().get(0).labelOrId();
		}

		try (VideoFile video = Videos.open(VIDEO)) {
			ObjectScanReport filtered = scanner().scan(video, options().setClassFilter(Set.of(present)));

			assertFalse(filtered.detections().isEmpty(), "the filter named a class the unfiltered scan had found");
			for (DetectedObject detection : filtered.detections()) {
				assertEquals(present, detection.labelOrId());
			}
		}
	}
}
