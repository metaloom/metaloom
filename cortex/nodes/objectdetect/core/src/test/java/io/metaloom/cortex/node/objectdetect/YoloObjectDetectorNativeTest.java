package io.metaloom.cortex.node.objectdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.video4j.Video4j;

/**
 * Exercises the real thing: {@code libyolib.so}, an ONNX Runtime and an actual model.
 *
 * <p>
 * Every other test in this module stubs {@link ObjectDetector}, which is right — the detection
 * algorithm is not what they are testing, and requiring a 17 MB model to assert a JSON shape would
 * make the suite unrunnable. But that leaves the one seam nothing covers: whether
 * {@link YoloObjectDetector} actually talks to the native library correctly.
 * </p>
 *
 * <p>
 * Opt in by pointing it at a model, which is why it does not run on CI:
 * </p>
 *
 * <pre>
 * mvn -o -pl cortex/nodes/objectdetect/core test -Dtest=YoloObjectDetectorNativeTest \
 *     -Dobjectdetect.model=/path/to/YOLOv11n_voc.onnx \
 *     -Dobjectdetect.labels=/path/to/voc.names \
 *     -Dobjectdetect.image=/path/to/dog.jpg
 * </pre>
 */
@EnabledIfSystemProperty(named = "objectdetect.model", matches = ".+")
class YoloObjectDetectorNativeTest {

	private static final String MODEL = System.getProperty("objectdetect.model");
	private static final String LABELS = System.getProperty("objectdetect.labels");
	private static final String IMAGE = System.getProperty("objectdetect.image");

	@BeforeAll
	static void initVideo4j() {
		Video4j.init();
	}

	/**
	 * CPU, not GPU: this is a wiring check, and asking for CUDA on a machine that has none turns a
	 * clear failure into an ONNX Runtime provider warning followed by the same answer, slower.
	 */
	private static ObjectDetector detector() {
		return new YoloObjectDetector(MODEL, LABELS, false, System.getProperty("yolo4j.onnxruntime.lib"));
	}

	@Test
	void testDetectsObjectsInARealImage() throws Exception {
		BufferedImage image = ImageIO.read(new File(IMAGE));
		assertNotNull(image, "could not decode " + IMAGE);

		List<ObjectDetection> detections = detector().detect(image);

		assertFalse(detections.isEmpty(), "expected the model to find something in " + IMAGE);
		for (ObjectDetection detection : detections) {
			// The label is the whole reason this node exists, and it is also the thing yolo4j got wrong:
			// its guard rejected class id 0, so the first class of every model resolved to null.
			assertNotNull(detection.label(), "class " + detection.classId() + " resolved to no label");
			assertTrue(detection.confidence() > 0f && detection.confidence() <= 1f,
				"confidence out of range: " + detection.confidence());

			BoundingBox box = detection.box();
			assertTrue(box.width() > 0 && box.height() > 0, "degenerate box: " + box);
			// Absolute pixels in the space of the image we passed in, not normalized - the contract the
			// ABSOLUTE_PIXELS marker on the detections port promises.
			assertTrue(box.x() < image.getWidth() && box.y() < image.getHeight(),
				"box " + box + " lies outside the " + image.getWidth() + "x" + image.getHeight() + " image");
		}
	}

	/**
	 * A second instance on the same model adopts the loaded detector; a different model is refused.
	 *
	 * <p>
	 * Two {@code objectdetect} nodes on one model, differing only in threshold or class filter, is an
	 * ordinary graph — the skip cache is keyed for exactly that case. Refusing the second one would be
	 * refusing a legal pipeline. A second <em>model</em> is a different matter: the native side holds
	 * one detector, so the alternative to failing is silently detecting with the wrong weights.
	 * </p>
	 */
	@Test
	void testASecondInstanceAdoptsTheSameModelButRefusesAnother() throws Exception {
		BufferedImage image = ImageIO.read(new File(IMAGE));
		assertFalse(detector().detect(image).isEmpty());

		assertFalse(detector().detect(image).isEmpty(), "an identically configured second instance must work");

		ObjectDetector other = new YoloObjectDetector(MODEL, LABELS, true, System.getProperty("yolo4j.onnxruntime.lib"));
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> other.detect(image));
		assertTrue(failure.getMessage().contains("already initialized"), failure.getMessage());
	}

	@Test
	void testTheLabelSetMatchesTheLabelsFile() throws Exception {
		List<String> labels = detector().labels();

		assertFalse(labels.isEmpty(), "no labels were loaded from " + LABELS);
		assertEquals(labels, List.copyOf(java.nio.file.Files.readAllLines(java.nio.file.Path.of(LABELS))),
			"the detector must report exactly the labels file, in order - the class ids index into it");
	}
}
