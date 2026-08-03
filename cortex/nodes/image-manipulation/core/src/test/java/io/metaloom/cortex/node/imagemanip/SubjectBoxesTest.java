package io.metaloom.cortex.node.imagemanip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Rect;
import io.vertx.core.json.JsonObject;

/**
 * Parsing the detection port's elements.
 *
 * <p>
 * The theme throughout is that one bad element must never fail an item: a malformed box among twenty good ones is dropped, and an image with no usable
 * detection at all is a legitimate outcome the fallback handles.
 * </p>
 */
class SubjectBoxesTest {

	private static final int W = 400;

	private static final int H = 200;

	@Test
	void testAWellFormedDetectionBecomesARectangle() {
		List<Rect> boxes = SubjectBoxes.parse(List.of(ImageManipFixtures.detection(10, 20, 30, 40)),
			Set.of("face"), 0.5d, W, H);
		assertEquals(List.of(new Rect(10, 20, 30, 40)), boxes);
	}

	@Test
	void testTypesAreFiltered() {
		List<String> elements = List.of(
			ImageManipFixtures.detection(10, 10, 20, 20, "face", 1.0d),
			ImageManipFixtures.detection(50, 50, 20, 20, "car", 1.0d));

		assertEquals(1, SubjectBoxes.parse(elements, Set.of("face"), 0.5d, W, H).size());
		assertEquals(2, SubjectBoxes.parse(elements, Set.of(), 0.5d, W, H).size(), "an empty type set must accept everything");
		assertEquals(2, SubjectBoxes.parse(elements, SubjectBoxes.types("*"), 0.5d, W, H).size());
		assertEquals(2, SubjectBoxes.parse(elements, SubjectBoxes.types("face,car"), 0.5d, W, H).size());
	}

	@Test
	void testLowConfidenceDetectionsAreDropped() {
		List<String> elements = List.of(
			ImageManipFixtures.detection(10, 10, 20, 20, "face", 0.9d),
			ImageManipFixtures.detection(50, 50, 20, 20, "face", 0.2d));
		assertEquals(1, SubjectBoxes.parse(elements, Set.of("face"), 0.5d, W, H).size());
	}

	@Test
	void testAnElementInAnUnknownCoordinateSpaceIsDroppedRatherThanMisread() {
		// 🔴 The detection table documents normalized 0-1 while the node writes absolute pixels. A box in
		// a space this node cannot convert from would be off by a factor of the image width.
		String normalized = new JsonObject()
			.put("type", "face")
			.put("bbox", new JsonObject().put("x", 0).put("y", 0).put("w", 1).put("h", 1))
			.put("coordinates", "NORMALIZED")
			.encode();
		assertTrue(SubjectBoxes.parse(List.of(normalized), Set.of("face"), 0.5d, W, H).isEmpty());
	}

	@Test
	void testAnElementWithNoMarkerIsTakenAsAbsolutePixels() {
		// Every producer in the tree emits absolute pixels today; only an explicit foreign marker is refused.
		String bare = new JsonObject()
			.put("type", "face")
			.put("bbox", new JsonObject().put("x", 10).put("y", 10).put("w", 20).put("h", 20))
			.encode();
		assertEquals(1, SubjectBoxes.parse(List.of(bare), Set.of("face"), 0.5d, W, H).size());
	}

	@Test
	void testMalformedElementsAreSkippedWithoutLosingTheGoodOnes() {
		List<String> elements = java.util.Arrays.asList(
			"not json at all",
			"",
			null,
			new JsonObject().put("type", "face").encode(),
			new JsonObject().put("type", "face").put("bbox", new JsonObject().put("x", 1)).encode(),
			ImageManipFixtures.detection(10, 20, 30, 40));

		assertEquals(List.of(new Rect(10, 20, 30, 40)), SubjectBoxes.parse(elements, Set.of("face"), 0.5d, W, H));
	}

	@Test
	void testABoxIsClampedIntoTheFrame() {
		List<Rect> boxes = SubjectBoxes.parse(List.of(ImageManipFixtures.detection(380, 190, 100, 100)), Set.of("face"), 0.5d, W, H);
		Rect box = boxes.get(0);
		assertTrue(box.x() + box.w() <= W && box.y() + box.h() <= H, "the box left the frame: " + box);
	}

	@Test
	void testAZeroSizedBoxIsDropped() {
		assertTrue(SubjectBoxes.parse(List.of(ImageManipFixtures.detection(10, 10, 0, 20)), Set.of("face"), 0.5d, W, H).isEmpty());
	}

	@Test
	void testTheDigestChangesWhenTheBoxesDo() {
		// 🔴 This is what stops a re-run against better detections being served the first run's crop.
		String one = SubjectBoxes.material(List.of(new Rect(10, 10, 20, 20)));
		String two = SubjectBoxes.material(List.of(new Rect(11, 10, 20, 20)));
		String order = SubjectBoxes.material(List.of(new Rect(11, 10, 20, 20), new Rect(10, 10, 20, 20)));

		assertNotEquals(one, two);
		assertNotEquals(two, order);
		assertEquals(one, SubjectBoxes.material(List.of(new Rect(10, 10, 20, 20))));
		assertEquals("", SubjectBoxes.material(List.of()));
	}

	@Test
	void testTypeParsingIsCaseAndWhitespaceInsensitive() {
		assertEquals(Set.of("face", "person"), SubjectBoxes.types(" Face , PERSON "));
		assertTrue(SubjectBoxes.types("").isEmpty());
		assertTrue(SubjectBoxes.types(null).isEmpty());
		assertTrue(SubjectBoxes.types("*").isEmpty());
	}

	@Test
	void testNullElementsAreTolerated() {
		assertTrue(SubjectBoxes.parse(null, Set.of("face"), 0.5d, W, H).isEmpty());
	}
}
