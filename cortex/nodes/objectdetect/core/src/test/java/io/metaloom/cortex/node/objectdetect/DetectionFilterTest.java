package io.metaloom.cortex.node.objectdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.payload.BoundingBox;

/**
 * The rules both paths share.
 *
 * <p>
 * Tested here rather than only through the node because the video path cannot be exercised without
 * the native Video4j runtime — and a filter that lives in two places is exactly how the image and
 * video paths of a detector node drift apart.
 * </p>
 */
class DetectionFilterTest {

	private static ObjectDetection detection(float confidence, String label) {
		return new ObjectDetection(new BoundingBox(10, 20, 30, 40), confidence, 7, label);
	}

	@Test
	void testKeepsADetectionAtOrAboveTheThreshold() {
		ObjectDetectNodeOptions options = new ObjectDetectNodeOptions().setMinConfidence(0.5f);

		assertNotNull(DetectionFilter.accept(detection(0.5f, "car"), 3, 1.0d, options), "the threshold is inclusive");
		assertNotNull(DetectionFilter.accept(detection(0.9f, "car"), 3, 1.0d, options));
		assertNull(DetectionFilter.accept(detection(0.49f, "car"), 3, 1.0d, options));
	}

	@Test
	void testCarriesTheFrameAndClassThrough() {
		DetectedObject kept = DetectionFilter.accept(detection(0.9f, "car"), 42, 1.0d, new ObjectDetectNodeOptions());

		assertNotNull(kept);
		assertEquals(42, kept.frameIndex());
		assertEquals(7, kept.classId());
		assertEquals("car", kept.label());
		assertEquals(0.9f, kept.confidence());
	}

	@Test
	void testAnEmptyFilterKeepsEveryClass() {
		ObjectDetectNodeOptions options = new ObjectDetectNodeOptions();

		assertNotNull(DetectionFilter.accept(detection(0.9f, "car"), 0, 1.0d, options));
		assertNotNull(DetectionFilter.accept(detection(0.9f, "aardvark"), 0, 1.0d, options));
		assertNotNull(DetectionFilter.accept(detection(0.9f, null), 0, 1.0d, options),
			"an unlabelled detection is still a box worth keeping when nothing is being filtered for");
	}

	@Test
	void testAFilterDropsEverythingItDoesNotName() {
		ObjectDetectNodeOptions options = new ObjectDetectNodeOptions().setClassFilter(Set.of("car"));

		assertNotNull(DetectionFilter.accept(detection(0.9f, "car"), 0, 1.0d, options));
		assertNotNull(DetectionFilter.accept(detection(0.9f, "CAR"), 0, 1.0d, options), "matching is case-insensitive");
		assertNull(DetectionFilter.accept(detection(0.9f, "person"), 0, 1.0d, options));
		// Nothing to match against, so it cannot satisfy a filter that names classes - keeping it would
		// mean a "car only" pass returning things that are not cars.
		assertNull(DetectionFilter.accept(detection(0.9f, null), 0, 1.0d, options));
	}

	@Test
	void testScalesTheBoxBackToNativeResolution() {
		// A 1920-wide frame downscaled to 960 for inference: every coordinate doubles on the way back.
		DetectedObject kept = DetectionFilter.accept(detection(0.9f, "car"), 0, 2.0d, new ObjectDetectNodeOptions());

		assertNotNull(kept);
		assertEquals(new BoundingBox(20, 40, 60, 80), kept.box());
	}

	@Test
	void testAnUnscaledBoxIsLeftExactlyAlone() {
		// Identity rather than "multiply by 1.0": rounding a box that never moved would be a silent
		// off-by-one on the image path, where the scale is always 1.
		BoundingBox box = new BoundingBox(3, 5, 7, 9);
		assertEquals(box, DetectionFilter.rescale(box, 1.0d));
	}

	@Test
	void testRescalingRoundsRatherThanTruncates() {
		// 1.5x: 10 -> 15, 21 -> 31.5 -> 32. Truncation would shrink every box by up to a pixel per edge.
		assertEquals(new BoundingBox(15, 32, 5, 5), DetectionFilter.rescale(new BoundingBox(10, 21, 3, 3), 1.5d));
	}
}
