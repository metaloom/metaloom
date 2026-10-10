package io.metaloom.cortex.node.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The reduction step: which pixels are visited, how many of them, and what the alpha gate throws
 * away. Every image here is built in code so the expected sample count and the expected colour are
 * both computable by hand.
 */
public class PixelSamplerTest {

	private static final int OPAQUE = 0xFF000000;

	@Test
	public void testASolidRegionYieldsExactlyItsOwnColourOncePerSample() {
		BufferedImage image = solid(8, 8, "#3B6EA5");
		double[] lab = PixelSampler.sampleLab(image, new Box(0, 0, 8, 8), 64, 128);

		assertThat(lab).hasSize(64 * 3);
		Lab expected = ColorSpaces.rgbToLab(Rgb.ofHex("#3B6EA5"));
		for (int i = 0; i < 64; i++) {
			assertThat(lab[i * 3]).as("L* of sample " + i).isCloseTo(expected.l(), within(1e-12));
			assertThat(lab[i * 3 + 1]).as("a* of sample " + i).isCloseTo(expected.a(), within(1e-12));
			assertThat(lab[i * 3 + 2]).as("b* of sample " + i).isCloseTo(expected.b(), within(1e-12));
		}
	}

	@Test
	public void testASinglePixelRegionYieldsExactlyOnePoint() {
		double[] lab = PixelSampler.sampleLab(solid(4, 4, "#FF0000"), new Box(1, 1, 1, 1), 40_000, 128);

		assertThat(lab).hasSize(3);
		assertThat(lab[0]).isCloseTo(ColorSpaces.rgbToLab(Rgb.ofHex("#FF0000")).l(), within(1e-12));
	}

	@Test
	public void testAnEmptyBoxYieldsNoPointsRatherThanThrowing() {
		BufferedImage image = solid(4, 4, "#FF0000");

		assertThat(PixelSampler.sampleLab(image, new Box(0, 0, 0, 4), 40_000, 128)).isEmpty();
		assertThat(PixelSampler.sampleLab(image, new Box(0, 0, 4, 0), 40_000, 128)).isEmpty();
		assertThat(PixelSampler.sampleLab(image, new Box(0, 0, 4, 4), 0, 128)).isEmpty();
		assertThat(PixelSampler.distinctColors(image, new Box(0, 0, 0, 0), 40_000, 128, 5)).isZero();
	}

	/**
	 * 🔴 The budget is an upper bound, not an estimate. Deriving one stride from the box <em>area</em>
	 * and then rounding each axis up independently overshoots on an extreme aspect ratio: a
	 * 10000x2 banner at a 1000-pixel budget used to visit ceil(10000/71) x ceil(2/71) = 141 columns
	 * on one row and, at tighter budgets, many times what was asked for. A panorama or a letterboxed
	 * crop is an ordinary input, so the bound has to hold for every shape.
	 */
	@Test
	public void testTheSampleBudgetHoldsForEveryAspectRatio() {
		for (int maxSamples : List.of(1, 4, 16, 256, 1000, 40_000)) {
			for (Box box : List.of(
				new Box(0, 0, 100, 100),
				new Box(0, 0, 100000, 1),
				new Box(0, 0, 1, 100000),
				new Box(0, 0, 10000, 2),
				new Box(0, 0, 3, 7919),
				new Box(0, 0, 1, 1))) {
				int step = PixelSampler.stride(box, maxSamples);
				long visited = (long) ((box.w() + step - 1) / step) * ((box.h() + step - 1) / step);

				assertThat(step).as("stride of " + box + " at " + maxSamples).isGreaterThanOrEqualTo(1);
				assertThat(visited)
					.as("pixels visited over " + box + " at a budget of " + maxSamples + " (stride " + step + ")")
					.isLessThanOrEqualTo(Math.max(1, maxSamples));
			}
		}
	}

	/** The stride must not be widened past what the budget needs, or a square image loses detail. */
	@Test
	public void testTheStrideIsTheSmallestOneThatFits() {
		// 100x100 at 10000 samples fits every pixel.
		assertThat(PixelSampler.stride(new Box(0, 0, 100, 100), 10_000)).isEqualTo(1);
		// 100x100 at 2500 needs every second pixel on both axes: 50 x 50 = 2500.
		assertThat(PixelSampler.stride(new Box(0, 0, 100, 100), 2_500)).isEqualTo(2);
		// A single row of 100 at a budget of 50 needs every second pixel, not every tenth.
		assertThat(PixelSampler.stride(new Box(0, 0, 100, 1), 50)).isEqualTo(2);
	}

	/** Both entry points have to walk the same grid, or k is capped against a different sample. */
	@Test
	public void testDistinctColorsCountsOverTheSameSampleAsSampleLab() {
		BufferedImage image = stripes(64, 64, List.of("#FF0000", "#00FF00", "#0000FF"));
		Box box = new Box(0, 0, 64, 64);

		assertThat(PixelSampler.distinctColors(image, box, 4096, 128, 8)).isEqualTo(3);
		assertThat(PixelSampler.sampleLab(image, box, 4096, 128)).hasSize(64 * 64 * 3);
	}

	@Test
	public void testDistinctColorsStopsAtItsCap() {
		BufferedImage image = stripes(64, 64, List.of("#FF0000", "#00FF00", "#0000FF", "#FFFF00", "#FF00FF"));

		assertThat(PixelSampler.distinctColors(image, new Box(0, 0, 64, 64), 4096, 128, 2)).isEqualTo(2);
	}

	/** A single-colour region has exactly one distinct value — the k cap the node relies on. */
	@Test
	public void testASingleColourRegionCountsOneDistinctValue() {
		assertThat(PixelSampler.distinctColors(solid(16, 16, "#808080"), new Box(0, 0, 16, 16), 4096, 128, 5))
			.isEqualTo(1);
	}

	/**
	 * Transparent pixels are skipped rather than flattened, so the RGB written behind a zero alpha
	 * must never reach the clusterer — a logo on a transparent background would otherwise come back
	 * as whatever the file happened to store there.
	 */
	@Test
	public void testTheAlphaGateSkipsRatherThanFlattens() {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		int red = Rgb.ofHex("#FF0000").packed();
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				// Left half opaque red, right half fully transparent white.
				image.setRGB(x, y, x < 2 ? (OPAQUE | red) : Rgb.ofHex("#FFFFFF").packed());
			}
		}

		double[] lab = PixelSampler.sampleLab(image, new Box(0, 0, 4, 4), 16, 128);

		assertThat(lab).as("only the eight opaque pixels survive").hasSize(8 * 3);
		Lab expected = ColorSpaces.rgbToLab(Rgb.ofHex("#FF0000"));
		for (int i = 0; i < 8; i++) {
			assertThat(lab[i * 3]).isCloseTo(expected.l(), within(1e-12));
		}
	}

	/** The threshold is a floor, not a boolean: alpha exactly at it is kept. */
	@Test
	public void testAlphaExactlyAtTheThresholdIsKept() {
		BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, (128 << 24) | Rgb.ofHex("#FF0000").packed());
		image.setRGB(1, 0, (127 << 24) | Rgb.ofHex("#FF0000").packed());

		assertThat(PixelSampler.sampleLab(image, new Box(0, 0, 2, 1), 16, 128))
			.as("alpha 128 is kept, alpha 127 is not")
			.hasSize(3);
	}

	/** An all-transparent region yields nothing at all, which is what the node reports as a skip. */
	@Test
	public void testAFullyTransparentRegionYieldsNoPoints() {
		BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				image.setRGB(x, y, Rgb.ofHex("#123456").packed());
			}
		}

		assertThat(PixelSampler.sampleLab(image, new Box(0, 0, 4, 4), 16, 128)).isEmpty();
		assertThat(PixelSampler.distinctColors(image, new Box(0, 0, 4, 4), 16, 128, 5)).isZero();
	}

	/** Sampling is stride-based and so exactly reproducible - half of why the palette is stable. */
	@Test
	public void testTheSameRegionSampledTwiceIsIdentical() {
		BufferedImage image = stripes(37, 41, List.of("#FF0000", "#00FF00", "#0000FF"));
		Box box = new Box(3, 5, 30, 30);

		assertThat(PixelSampler.sampleLab(image, box, 100, 128))
			.isEqualTo(PixelSampler.sampleLab(image, box, 100, 128));
	}

	/** A box is indexed from its own origin, so only the region's pixels are read. */
	@Test
	public void testOnlyTheRegionIsRead() {
		BufferedImage image = stripes(4, 1, List.of("#FF0000", "#00FF00", "#0000FF", "#FFFFFF"));

		double[] lab = PixelSampler.sampleLab(image, new Box(1, 0, 1, 1), 40_000, 128);
		Lab green = ColorSpaces.rgbToLab(Rgb.ofHex("#00FF00"));

		assertThat(lab[0]).isCloseTo(green.l(), within(1e-12));
		assertThat(lab[1]).isCloseTo(green.a(), within(1e-12));
		assertThat(lab[2]).isCloseTo(green.b(), within(1e-12));
	}

	private static BufferedImage solid(int w, int h, String hex) {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int rgb = Rgb.ofHex(hex).packed();
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setRGB(x, y, rgb);
			}
		}
		return image;
	}

	/** One colour per column, cycling, so the distinct count is exactly the palette size. */
	private static BufferedImage stripes(int w, int h, List<String> hexes) {
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				image.setRGB(x, y, Rgb.ofHex(hexes.get(x % hexes.size())).packed());
			}
		}
		return image;
	}
}
