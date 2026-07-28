package io.metaloom.cortex.node.scenelayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.File;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sampling arithmetic for {@link DepthSampler} and {@link DepthMap}, on hand-built nearness fields
 * where the right answer is known exactly.
 */
class DepthSamplerTest {

	/** 16-bit quantisation costs about 1.5e-5; anything inside 1e-3 is an exact match in practice. */
	private static final double TOL = 1e-3;

	@TempDir
	File tempDir;

	private DepthMap splitMap(int width, int height, int imageWidth, int imageHeight) throws Exception {
		File file = new File(tempDir, "split.png");
		SceneLayoutFixtures.writeSplitMap(file, width, height);
		return DepthMap.read(file, imageWidth, imageHeight);
	}

	@Test
	void testReadsSixteenBitValuesAsNearness() throws Exception {
		DepthMap map = splitMap(200, 100, 200, 100);

		assertThat(map.nearnessAt(10, 10)).isCloseTo(0.9, within(TOL));
		assertThat(map.nearnessAt(190, 10)).isCloseTo(0.1, within(TOL));
	}

	@Test
	void testCoordinatesOutsideTheMapAreClamped() throws Exception {
		DepthMap map = splitMap(200, 100, 200, 100);

		// Clamping rather than throwing: a box projected from a slightly different image size can
		// legitimately graze the edge, and losing the whole object over one pixel would be worse.
		assertThat(map.nearnessAt(-5, -5)).isCloseTo(map.nearnessAt(0, 0), within(TOL));
		assertThat(map.nearnessAt(9999, 9999)).isCloseTo(map.nearnessAt(199, 99), within(TOL));
	}

	@Test
	void testProjectionScalesImageBoxesIntoMapSpace() throws Exception {
		// Map is half the image's size in both axes - the classic maxDim downscale.
		DepthMap map = splitMap(200, 100, 400, 200);

		BoxF projected = map.projectFromImage(new BoxF(100, 50, 40, 20));

		assertThat(projected.x()).isCloseTo(50, within(TOL));
		assertThat(projected.y()).isCloseTo(25, within(TOL));
		assertThat(projected.w()).isCloseTo(20, within(TOL));
		assertThat(projected.h()).isCloseTo(10, within(TOL));
	}

	@Test
	void testSamplesTheCoreNotTheWholeBox() throws Exception {
		// A box straddling the split, but whose centre sits entirely in the near half. Sampling the
		// whole box would average the two halves; sampling the core must report the near value.
		DepthMap map = splitMap(200, 100, 200, 100);

		BoxF straddling = new BoxF(60, 20, 60, 40);

		DepthStats core = DepthSampler.sample(map, straddling, 0.30, 1);
		assertThat(core.near()).isCloseTo(0.9, within(TOL));

		DepthStats whole = DepthSampler.sample(map, straddling, 0.0, 1);
		assertThat(whole.spread()).as("sampling the whole box picks up both depth planes").isGreaterThan(0.5);
	}

	@Test
	void testQuartilesAndSpreadOnAFlatRegion() throws Exception {
		File file = new File(tempDir, "flat.png");
		SceneLayoutFixtures.writeFlatMap(file, 100, 100, 0.42);
		DepthMap map = DepthMap.read(file, 100, 100);

		DepthStats stats = DepthSampler.sample(map, new BoxF(10, 10, 40, 40), 0.25, 1);

		assertThat(stats.near()).isCloseTo(0.42, within(TOL));
		assertThat(stats.spread()).isCloseTo(0.0, within(TOL));
		assertThat(stats.pixels()).isGreaterThan(0);
	}

	@Test
	void testTinyBoxIsRejected() throws Exception {
		DepthMap map = splitMap(200, 100, 200, 100);

		// A 2x2 box insets to under one pixel of core - a statistic from that is noise, not depth.
		assertThat(DepthSampler.sample(map, new BoxF(10, 10, 2, 2), 0.25, 16)).isNull();
	}

	@Test
	void testBoxFullyOutsideTheMapIsRejected() throws Exception {
		DepthMap map = splitMap(200, 100, 200, 100);

		assertThat(DepthSampler.sample(map, new BoxF(500, 500, 20, 20), 0.25, 1)).isNull();
	}

	@Test
	void testSceneQuantilesOrderCorrectly() throws Exception {
		File file = new File(tempDir, "ramp.png");
		SceneLayoutFixtures.writeRampMap(file, 200, 100);
		DepthMap map = DepthMap.read(file, 200, 100);

		assertThat(map.sceneQuantile(0.33)).isCloseTo(0.33, within(0.02));
		assertThat(map.sceneQuantile(0.66)).isCloseTo(0.66, within(0.02));
		assertThat(map.sceneQuantile(0.33)).isLessThan(map.sceneQuantile(0.66));
	}

	@Test
	void testCoreInsetNeverCollapsesTheBox() {
		// An absurd inset must still leave something to sample rather than producing a zero-area box.
		BoxF core = new BoxF(0, 0, 100, 100).core(0.9);

		assertThat(core.w()).isGreaterThan(0);
		assertThat(core.h()).isGreaterThan(0);
	}
}
