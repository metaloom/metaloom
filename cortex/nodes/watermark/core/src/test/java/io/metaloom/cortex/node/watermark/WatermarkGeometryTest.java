package io.metaloom.cortex.node.watermark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.watermark.WatermarkGeometry.Placement;

/**
 * Unit test for {@link WatermarkGeometry} - the pure placement arithmetic shared by the image and the video path.
 *
 * <p>
 * This is the node's highest-value test: it is the only place a placement bug can live, and it needs neither ImageIO nor ffmpeg to catch one.
 * </p>
 */
class WatermarkGeometryTest {

	@Test
	void testTopLeftIsFlushAgainstTheOrigin() {
		Placement placement = WatermarkGeometry.place(1000, 500, 100, 50, 0.2, 0.0, 0.0);
		assertEquals(0, placement.x());
		assertEquals(0, placement.y());
	}

	@Test
	void testBottomRightIsFlushAgainstTheOppositeEdge() {
		// The whole point of measuring against the inset box: at relX/relY = 1.0 the overlay's far edge touches the frame's, and no part of it is outside.
		Placement placement = WatermarkGeometry.place(1000, 500, 100, 50, 0.2, 1.0, 1.0);
		assertEquals(1000 - placement.width(), placement.x());
		assertEquals(500 - placement.height(), placement.y());
		assertTrue(placement.x() + placement.width() <= 1000);
		assertTrue(placement.y() + placement.height() <= 500);
	}

	@Test
	void testHalfCentresTheOverlayItselfNotItsLeftEdge() {
		Placement placement = WatermarkGeometry.place(1000, 600, 100, 100, 0.2, 0.5, 0.5);
		// width = 1000 * 0.2 = 200, height = 200 (square source) -> x = (1000-200)/2, y = (600-200)/2
		assertEquals(200, placement.width());
		assertEquals(200, placement.height());
		assertEquals(400, placement.x());
		assertEquals(200, placement.y());
		assertEquals(placement.x() + placement.width(), 1000 - placement.x(), "the overlay should be symmetric about the frame centre");
	}

	@Test
	void testScaleIsRelativeToTheMediaWidthAndPreservesAspect() {
		// A 400x100 overlay (4:1) at scale 0.25 of a 1600px-wide frame -> 400x100.
		Placement placement = WatermarkGeometry.place(1600, 900, 400, 100, 0.25, 0.0, 0.0);
		assertEquals(400, placement.width());
		assertEquals(100, placement.height());
	}

	@Test
	void testScaleZeroKeepsTheOverlaysNativeSize() {
		Placement placement = WatermarkGeometry.place(1600, 900, 137, 41, 0.0, 0.0, 0.0);
		assertEquals(137, placement.width());
		assertEquals(41, placement.height());
	}

	@Test
	void testOverlayWiderThanTheFrameIsClampedToIt() {
		Placement placement = WatermarkGeometry.place(100, 100, 4000, 4000, 1.0, 0.0, 0.0);
		assertEquals(100, placement.width());
		assertEquals(100, placement.height());
		assertEquals(0, placement.x());
		assertEquals(0, placement.y());
	}

	@Test
	void testTallOverlayOnAShortFrameGivesUpWidthNotAspect() {
		// A 100x1000 (1:10) overlay at scale 1.0 of a 1000x100 frame: the requested 1000px width would need 10000px of height. The height is capped at the
		// frame and the width recomputed, so the logo stays 1:10 rather than being squashed to 1000x100.
		Placement placement = WatermarkGeometry.place(1000, 100, 100, 1000, 1.0, 0.0, 0.0);
		assertEquals(100, placement.height());
		assertEquals(10, placement.width());
		assertEquals(10.0d, (double) placement.height() / placement.width(), 0.001d);
	}

	@Test
	void testDimensionsAreNeverZero() {
		// A tiny scale on a small frame rounds to zero pixels; a zero-width draw is a silent no-op rather than an error, so it is floored at one.
		Placement placement = WatermarkGeometry.place(10, 10, 100, 100, 0.001, 0.0, 0.0);
		assertTrue(placement.width() >= 1, "width should be floored at 1 but was " + placement.width());
		assertTrue(placement.height() >= 1, "height should be floored at 1 but was " + placement.height());
	}

	@Test
	void testOutOfRangeFactorsAreClampedRatherThanPlacingOffFrame() {
		Placement low = WatermarkGeometry.place(1000, 500, 100, 50, 0.2, -3.0, -3.0);
		assertEquals(0, low.x());
		assertEquals(0, low.y());

		Placement high = WatermarkGeometry.place(1000, 500, 100, 50, 0.2, 7.0, 7.0);
		assertEquals(1000 - high.width(), high.x());
		assertEquals(500 - high.height(), high.y());
	}

	@Test
	void testNonPositiveDimensionsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> WatermarkGeometry.place(0, 500, 100, 50, 0.2, 0.5, 0.5));
		assertThrows(IllegalArgumentException.class, () -> WatermarkGeometry.place(1000, 0, 100, 50, 0.2, 0.5, 0.5));
		assertThrows(IllegalArgumentException.class, () -> WatermarkGeometry.place(1000, 500, 0, 50, 0.2, 0.5, 0.5));
		assertThrows(IllegalArgumentException.class, () -> WatermarkGeometry.place(1000, 500, 100, 0, 0.2, 0.5, 0.5));
	}
}
