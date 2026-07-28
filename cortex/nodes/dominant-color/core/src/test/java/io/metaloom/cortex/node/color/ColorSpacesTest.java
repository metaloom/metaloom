package io.metaloom.cortex.node.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

public class ColorSpacesTest {

	@Test
	public void testBlackAndWhiteAnchorTheLightnessAxis() {
		Lab black = ColorSpaces.rgbToLab(Rgb.ofHex("#000000"));
		assertThat(black.l()).isCloseTo(0d, within(1e-9));
		assertThat(black.a()).isCloseTo(0d, within(1e-9));
		assertThat(black.b()).isCloseTo(0d, within(1e-9));

		Lab white = ColorSpaces.rgbToLab(Rgb.ofHex("#FFFFFF"));
		assertThat(white.l()).isCloseTo(100d, within(0.01d));
		assertThat(white.a()).isCloseTo(0d, within(0.01d));
		assertThat(white.b()).isCloseTo(0d, within(0.01d));
	}

	@Test
	public void testPrimariesMatchTheirPublishedLabValues() {
		assertLab("#FF0000", 53.24d, 80.09d, 67.20d);
		assertLab("#00FF00", 87.73d, -86.18d, 83.18d);
		assertLab("#0000FF", 32.30d, 79.19d, -107.86d);
	}

	/**
	 * The round trip is the test that catches an EPS/KAPPA mismatch: rounded transfer constants
	 * make {@code f} and its inverse discontinuous at the join, which shows up as off-by-one
	 * channels on dark colours and nowhere else.
	 */
	@Test
	public void testEveryWebSafeColorSurvivesARoundTrip() {
		for (int r = 0; r < 256; r += 51) {
			for (int g = 0; g < 256; g += 51) {
				for (int b = 0; b < 256; b += 51) {
					Rgb original = new Rgb(r, g, b);
					assertThat(ColorSpaces.labToRgb(ColorSpaces.rgbToLab(original)))
						.as("round trip of " + original.hex())
						.isEqualTo(original);
				}
			}
		}
	}

	@Test
	public void testEveryDarkGreySurvivesARoundTrip() {
		for (int v = 0; v < 40; v++) {
			Rgb original = new Rgb(v, v, v);
			assertThat(ColorSpaces.labToRgb(ColorSpaces.rgbToLab(original)))
				.as("round trip of " + original.hex())
				.isEqualTo(original);
		}
	}

	@Test
	public void testOutOfGamutLabIsClampedRatherThanWrapped() {
		Rgb clamped = ColorSpaces.labToRgb(new Lab(50, 120, -120));
		assertThat(clamped.r()).isBetween(0, 255);
		assertThat(clamped.g()).isBetween(0, 255);
		assertThat(clamped.b()).isBetween(0, 255);
	}

	@Test
	public void testHueIsNullForAchromaticColors() {
		assertThat(ColorSpaces.rgbToLab(new Rgb(128, 128, 128)).hue()).isNull();
		assertThat(ColorSpaces.rgbToLab(new Rgb(255, 0, 0)).hue()).isNotNull();
	}

	@Test
	public void testHslMatchesTheCssDefinition() {
		Hsl red = ColorSpaces.rgbToHsl(new Rgb(255, 0, 0));
		assertThat(red.h()).isCloseTo(0d, within(0.01d));
		assertThat(red.s()).isCloseTo(100d, within(0.01d));
		assertThat(red.l()).isCloseTo(50d, within(0.01d));

		Hsl grey = ColorSpaces.rgbToHsl(new Rgb(128, 128, 128));
		assertThat(grey.s()).isCloseTo(0d, within(0.01d));
		assertThat(grey.l()).isCloseTo(50.196d, within(0.01d));

		Hsl teal = ColorSpaces.rgbToHsl(new Rgb(0, 128, 128));
		assertThat(teal.h()).isCloseTo(180d, within(0.01d));
	}

	@Test
	public void testHexIsUppercaseAndZeroPadded() {
		assertThat(new Rgb(59, 110, 165).hex()).isEqualTo("#3B6EA5");
		assertThat(new Rgb(0, 0, 255).hex()).isEqualTo("#0000FF");
		assertThat(new Rgb(0, 0, 0).hex()).isEqualTo("#000000");
	}

	@Test
	public void testPackedRoundTripIgnoresAlpha() {
		assertThat(Rgb.ofPacked(0xFF3B6EA5)).isEqualTo(new Rgb(59, 110, 165));
		assertThat(Rgb.ofPacked(0x003B6EA5)).isEqualTo(new Rgb(59, 110, 165));
		assertThat(ColorSpaces.packedToLab(0xFF3B6EA5)).isEqualTo(ColorSpaces.rgbToLab(new Rgb(59, 110, 165)));
	}

	private void assertLab(String hex, double l, double a, double b) {
		Lab lab = ColorSpaces.rgbToLab(Rgb.ofHex(hex));
		assertThat(lab.l()).as(hex + " L*").isCloseTo(l, within(0.01d));
		assertThat(lab.a()).as(hex + " a*").isCloseTo(a, within(0.01d));
		assertThat(lab.b()).as(hex + " b*").isCloseTo(b, within(0.01d));
	}
}
