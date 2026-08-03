package io.metaloom.cortex.node.imagemanip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Rect;
import io.metaloom.cortex.node.imagemanip.ManipulationGeometry.Size;

/**
 * The arithmetic, with no pixels in sight.
 *
 * <p>
 * Every composition defect the node can have shows up here first, which is the point of keeping {@link ManipulationGeometry} pure: these run in
 * microseconds and assert integers, so a wrong rectangle is a failing number rather than a picture someone has to look at.
 * </p>
 */
class ManipulationGeometryTest {

	// ── orientation ──────────────────────────────────────────────────────

	@Test
	void testQuarterTurnOrientationsSwapTheFrameDimensions() {
		for (Orientation orientation : Orientation.values()) {
			Size size = ManipulationGeometry.transform(orientation, 400, 200);
			if (orientation.swapsAxes()) {
				assertEquals(new Size(200, 400), size, orientation + " is a quarter turn and must swap the axes");
			} else {
				assertEquals(new Size(400, 200), size, orientation + " is not a quarter turn and must keep the axes");
			}
		}
	}

	@Test
	void testTheFourMirroredOrientationsAreNotTreatedAsPlainRotations() {
		// The quiet defect: a rotation-only implementation gets 2, 4, 5 and 7 silently wrong rather than
		// failing on them. Pinning which ones mirror is what stops that being reintroduced.
		assertTrue(Orientation.MIRROR_HORIZONTAL.mirrored());
		assertTrue(Orientation.MIRROR_VERTICAL.mirrored());
		assertTrue(Orientation.TRANSPOSE.mirrored());
		assertTrue(Orientation.TRANSVERSE.mirrored());

		assertFalse(Orientation.NORMAL.mirrored());
		assertFalse(Orientation.ROTATE_90.mirrored());
		assertFalse(Orientation.ROTATE_180.mirrored());
		assertFalse(Orientation.ROTATE_270.mirrored());
	}

	@Test
	void testTransposeAndTransverseUseTheEncodingTheExifTableSpecifies() {
		// EXIF 5 is "mirror horizontal then rotate 270 CW"; EXIF 7 is "mirror horizontal then rotate 90 CW".
		// Swapping the two is the easiest way to get this table subtly wrong.
		assertEquals(270, Orientation.TRANSPOSE.degrees());
		assertEquals(90, Orientation.TRANSVERSE.degrees());
	}

	@Test
	void testARotatedBoxLandsWhereTheRotatedPixelsDo() {
		// A 100x50 box at (10,20) in a 400x200 frame, turned a quarter clockwise into a 200x400 frame.
		// Its left edge is measured from what used to be the bottom: 200 - 20 - 50 = 130.
		Rect rotated = ManipulationGeometry.transform(Orientation.ROTATE_90, new Rect(10, 20, 100, 50), 400, 200);
		assertEquals(new Rect(130, 10, 50, 100), rotated);
	}

	@Test
	void testEveryOrientationKeepsABoxInsideTheTransformedFrame() {
		// 🔴 The subject-crop trap. A box that leaves the frame under any orientation means a later crop
		// reads coordinates that do not exist.
		Rect box = new Rect(10, 20, 100, 50);
		for (Orientation orientation : Orientation.values()) {
			Size frame = ManipulationGeometry.transform(orientation, 400, 200);
			Rect moved = ManipulationGeometry.transform(orientation, box, 400, 200);
			assertTrue(moved.x() >= 0 && moved.y() >= 0, orientation + " pushed the box to " + moved);
			assertTrue(moved.x() + moved.w() <= frame.w() && moved.y() + moved.h() <= frame.h(),
				orientation + " pushed the box out of the " + frame.w() + "x" + frame.h() + " frame: " + moved);
			assertEquals(box.w() * box.h(), moved.w() * moved.h(), orientation + " changed the box's area");
		}
	}

	@Test
	void testRotatingFourQuarterTurnsReturnsTheBoxToWhereItStarted() {
		Rect box = new Rect(10, 20, 100, 50);
		Rect once = ManipulationGeometry.transform(Orientation.ROTATE_90, box, 400, 200);
		Rect twice = ManipulationGeometry.transform(Orientation.ROTATE_90, once, 200, 400);
		Rect thrice = ManipulationGeometry.transform(Orientation.ROTATE_90, twice, 400, 200);
		Rect fourth = ManipulationGeometry.transform(Orientation.ROTATE_90, thrice, 200, 400);
		assertEquals(box, fourth);
	}

	@Test
	void testAMirroredOrientationMovesTheBoxToTheOppositeSide() {
		Rect box = new Rect(10, 20, 100, 50);
		Rect mirrored = ManipulationGeometry.transform(Orientation.MIRROR_HORIZONTAL, box, 400, 200);
		assertEquals(new Rect(400 - 10 - 100, 20, 100, 50), mirrored);
	}

	@Test
	void testTheIdentityOrientationChangesNothing() {
		Rect box = new Rect(10, 20, 100, 50);
		assertEquals(box, ManipulationGeometry.transform(Orientation.NORMAL, box, 400, 200));
	}

	@Test
	void testAnUnknownOrUnsetExifValueMeansNormal() {
		assertEquals(Orientation.NORMAL, Orientation.ofExif(null));
		assertEquals(Orientation.NORMAL, Orientation.ofExif(0));
		assertEquals(Orientation.NORMAL, Orientation.ofExif(99));
		assertEquals(Orientation.ROTATE_90, Orientation.ofExif(6));
	}

	// ── crops ────────────────────────────────────────────────────────────

	@Test
	void testTheDefaultRelativeCropIsTheWholeFrame() {
		assertEquals(new Rect(0, 0, 400, 200), ManipulationGeometry.relativeCrop(400, 200, 0d, 0d, 1d, 1d));
	}

	@Test
	void testARelativeCropIsResolutionIndependent() {
		// The same fractions against two resolutions must describe the same region of the picture.
		Rect small = ManipulationGeometry.relativeCrop(400, 200, 0.25d, 0.5d, 0.5d, 0.5d);
		Rect large = ManipulationGeometry.relativeCrop(800, 400, 0.25d, 0.5d, 0.5d, 0.5d);
		assertEquals(new Rect(100, 100, 200, 100), small);
		assertEquals(new Rect(200, 200, 400, 200), large);
	}

	@Test
	void testACropWindowIsPulledBackInsideTheFrame() {
		Rect rect = ManipulationGeometry.relativeCrop(400, 200, 0.9d, 0.9d, 0.5d, 0.5d);
		assertTrue(rect.x() + rect.w() <= 400, "the window left the frame: " + rect);
		assertTrue(rect.y() + rect.h() <= 200, "the window left the frame: " + rect);
	}

	@Test
	void testClampNeverProducesAZeroSizedRectangle() {
		// getSubimage throws on a zero-sized window, so this is the invariant the whole class exists to keep.
		Rect rect = ManipulationGeometry.clamp(new Rect(500, 500, 0, 0), 400, 200);
		assertTrue(rect.w() >= 1 && rect.h() >= 1, "clamp produced " + rect);
		assertTrue(rect.x() + rect.w() <= 400 && rect.y() + rect.h() <= 200, "clamp produced " + rect);
	}

	// ── subjects ─────────────────────────────────────────────────────────

	@Test
	void testTheUnionBoundsEveryBox() {
		Rect union = ManipulationGeometry.union(List.of(new Rect(10, 10, 20, 20), new Rect(100, 50, 30, 30)));
		assertEquals(new Rect(10, 10, 120, 70), union);
	}

	@Test
	void testUnionRefusesAnEmptyList() {
		assertThrows(IllegalArgumentException.class, () -> ManipulationGeometry.union(List.of()));
	}

	@Test
	void testPaddingIsRelativeToTheBoxNotTheFrame() {
		// A small face and a large one must both gain room proportional to themselves.
		assertEquals(new Rect(90, 90, 40, 40), ManipulationGeometry.pad(new Rect(100, 100, 20, 20), 0.5d));
		assertEquals(new Rect(0, 0, 400, 400), ManipulationGeometry.pad(new Rect(100, 100, 200, 200), 0.5d));
	}

	@Test
	void testExpandToAspectGrowsTheShortAxisRatherThanCuttingTheLongOne() {
		// 🔴 Reaching 16:9 by trimming would cut the very subjects the box was built around.
		Rect subject = new Rect(100, 100, 100, 100);
		Rect framed = ManipulationGeometry.expandToAspect(subject, 16d / 9d, 1000, 1000);

		assertEquals(100, framed.h(), "the height was cut instead of the width being grown");
		assertTrue(framed.w() > subject.w(), "the width should have grown, got " + framed);
		assertEquals(16d / 9d, (double) framed.w() / framed.h(), 0.02d);
	}

	@Test
	void testExpandToAspectGivesUpSizeRatherThanRatioWhenItCannotFit() {
		// A box that would have to leave the frame to reach the ratio shrinks instead - a smaller
		// rectangle still frames the subject, a wrongly shaped one defeats the operation.
		Rect framed = ManipulationGeometry.expandToAspect(new Rect(0, 0, 400, 400), 16d / 9d, 400, 400);
		assertEquals(16d / 9d, (double) framed.w() / framed.h(), 0.02d);
		assertTrue(framed.w() <= 400 && framed.h() <= 400, "the rectangle left the frame: " + framed);
	}

	@Test
	void testExpandToAspectKeepsTheSubjectCentred() {
		Rect subject = new Rect(400, 400, 100, 100);
		Rect framed = ManipulationGeometry.expandToAspect(subject, 1d, 1000, 1000);
		assertEquals(subject.centerX(), framed.centerX(), 1.0d);
		assertEquals(subject.centerY(), framed.centerY(), 1.0d);
	}

	@Test
	void testAnEmptyAspectLeavesTheRectangleAlone() {
		Rect subject = new Rect(10, 20, 100, 50);
		assertEquals(subject, ManipulationGeometry.expandToAspect(subject, 0d, 400, 200));
	}

	// ── aspect ───────────────────────────────────────────────────────────

	@Test
	void testCentreAspectIsTheLargestCentredWindowOfThatRatio() {
		Rect square = ManipulationGeometry.centreAspect(400, 200, 1d);
		assertEquals(new Rect(100, 0, 200, 200), square);
	}

	@Test
	void testPadToAspectGrowsAPortraitFrameSidewaysForTheVerticalVideoFix() {
		// A 3:4 portrait against 16:9: the canvas keeps the height and gains width, which is exactly the
		// region padFill then has to fill.
		Size canvas = ManipulationGeometry.padToAspect(600, 800, 16d / 9d);
		assertEquals(800, canvas.h(), "padding must not crop the picture");
		assertTrue(canvas.w() > 600, "the canvas should have grown sideways, got " + canvas);
		assertEquals(16d / 9d, (double) canvas.w() / canvas.h(), 0.01d);
	}

	@Test
	void testPadToAspectGrowsALandscapeFrameDownwardsForAPortraitTarget() {
		Size canvas = ManipulationGeometry.padToAspect(1600, 900, 9d / 16d);
		assertEquals(1600, canvas.w());
		assertTrue(canvas.h() > 900, "the canvas should have grown taller, got " + canvas);
	}

	@Test
	void testPaddingNeverDiscardsPixels() {
		Size canvas = ManipulationGeometry.padToAspect(600, 800, 16d / 9d);
		assertTrue(canvas.w() >= 600 && canvas.h() >= 800, "the pad canvas is smaller than the source: " + canvas);
	}

	// ── resize ───────────────────────────────────────────────────────────

	@Test
	void testResizeBoundsTheLongEdgeAndKeepsTheRatio() {
		Size size = ManipulationGeometry.resizeBounds(4000, 3000, 1000, false);
		assertEquals(new Size(1000, 750), size);
	}

	@Test
	void testResizeDoesNotEnlargeUnlessAskedTo() {
		assertEquals(new Size(400, 200), ManipulationGeometry.resizeBounds(400, 200, 4000, false));
		assertEquals(new Size(4000, 2000), ManipulationGeometry.resizeBounds(400, 200, 4000, true));
	}

	@Test
	void testAZeroBoundDisablesResizing() {
		assertEquals(new Size(400, 200), ManipulationGeometry.resizeBounds(400, 200, 0, false));
	}

	@Test
	void testResizeNeverCollapsesAnEdgeToZero() {
		Size size = ManipulationGeometry.resizeBounds(4000, 3, 10, false);
		assertTrue(size.w() >= 1 && size.h() >= 1, "an edge collapsed: " + size);
	}

	// ── parsing ──────────────────────────────────────────────────────────

	@Test
	void testAspectRatiosParseFromTheFormAnAuthorWrites() {
		assertEquals(16d / 9d, ManipulationGeometry.parseAspect("16:9"), 0.0001d);
		assertEquals(1d, ManipulationGeometry.parseAspect(" 1 : 1 "), 0.0001d);
		assertEquals(1.5d, ManipulationGeometry.parseAspect("1.5"), 0.0001d);
		assertEquals(0d, ManipulationGeometry.parseAspect(""), 0.0001d);
		assertEquals(0d, ManipulationGeometry.parseAspect(null), 0.0001d);
	}

	@Test
	void testAMalformedAspectIsRejectedRatherThanQuietlyBecomingSomethingElse() {
		assertThrows(IllegalArgumentException.class, () -> ManipulationGeometry.parseAspect("16/9"));
		assertThrows(IllegalArgumentException.class, () -> ManipulationGeometry.parseAspect("wide"));
		assertThrows(IllegalArgumentException.class, () -> ManipulationGeometry.parseAspect("16:0"));
		assertThrows(IllegalArgumentException.class, () -> ManipulationGeometry.parseAspect("-2"));

		assertFalse(ManipulationGeometry.isAspect("16/9"));
		assertTrue(ManipulationGeometry.isAspect("16:9"));
		assertTrue(ManipulationGeometry.isAspect(""));
	}

	@Test
	void testColoursParseWithOrWithoutTheHash() {
		assertEquals(0xFF000000, ManipulationGeometry.parseColor("#000000"));
		assertEquals(0xFFFFFFFF, ManipulationGeometry.parseColor("FFFFFF"));
		assertEquals(0xFFFF0000, ManipulationGeometry.parseColor("#ff0000"));

		assertThrows(IllegalArgumentException.class, () -> ManipulationGeometry.parseColor("#fff"));
		assertThrows(IllegalArgumentException.class, () -> ManipulationGeometry.parseColor("red"));
		assertFalse(ManipulationGeometry.isColor("#12345"));
	}

	// ── carrying boxes through the chain ─────────────────────────────────

	@Test
	void testTranslateAndScaleCarryABoxThroughACropAndAResize() {
		Rect box = new Rect(100, 100, 50, 50);
		Rect afterCrop = ManipulationGeometry.translate(box, -80, -90);
		assertEquals(new Rect(20, 10, 50, 50), afterCrop);

		Rect afterResize = ManipulationGeometry.scale(afterCrop, 0.5d, 0.5d);
		assertEquals(new Rect(10, 5, 25, 25), afterResize);
	}

	@Test
	void testIntersectsDetectsABoxACropRemovedEntirely() {
		assertTrue(ManipulationGeometry.intersects(new Rect(-10, -10, 50, 50), 100, 100));
		assertFalse(ManipulationGeometry.intersects(new Rect(-60, 0, 50, 50), 100, 100));
		assertFalse(ManipulationGeometry.intersects(new Rect(100, 0, 50, 50), 100, 100));
	}
}
