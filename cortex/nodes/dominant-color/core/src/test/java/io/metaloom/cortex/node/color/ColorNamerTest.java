package io.metaloom.cortex.node.color;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.node.color.ColorTerms.Prototype;
import io.metaloom.cortex.node.color.ColorTerms.Term;

public class ColorNamerTest {

	private final ColorNamer namer = ColorNamer.defaults();

	@Test
	public void testEveryPrototypeNamesItsOwnTerm() {
		for (Prototype prototype : ColorTerms.chromaticPrototypes()) {
			ColorName name = namer.name(prototype.lab());
			assertThat(name.term())
				.as(prototype.hex() + " should name itself " + prototype.termKey())
				.isEqualTo(prototype.termKey());
			assertThat(name.distance()).as(prototype.hex() + " distance").isCloseTo(0d, within(1e-9));
		}
	}

	/**
	 * The three colours that a single-anchor-per-term table gets wrong. Navy and midnightblue both
	 * come out purple against one blue anchor, and pure green comes out yellow by a tenth of a
	 * delta-E. If someone thins the prototype codebook, these fail first.
	 */
	@Test
	public void testTheColorsThatMotivatedTheMultiPrototypeCodebook() {
		assertThat(name("#000080").term()).as("navy").isEqualTo("blue");
		assertThat(name("#191970").term()).as("midnightblue").isEqualTo("blue");
		assertThat(name("#00FF00").term()).as("pure green").isEqualTo("green");
	}

	@Test
	public void testAchromaticColorsAreNamedOnLightnessAlone() {
		assertName("#000000", "black", "black", "Schwarz");
		assertName("#303030", "black", "black", "Schwarz");
		assertName("#404040", "grey", "dark grey", "dunkles Grau");
		assertName("#555555", "grey", "grey", "Grau");
		assertName("#808080", "grey", "grey", "Grau");
		assertName("#708090", "grey", "grey", "Grau");
		assertName("#C0C0C0", "grey", "light grey", "helles Grau");
		assertName("#E8E8E8", "white", "white", "Weiß");
		assertName("#FFFFFF", "white", "white", "Weiß");
	}

	/**
	 * The invariant the achromatic gate exists to guarantee: a grey never picks up a chroma
	 * modifier, so {@code greyish grey} cannot be produced.
	 */
	@Test
	public void testAchromaticColorsNeverReceiveAChromaModifier() {
		for (String hex : List.of("#000000", "#303030", "#404040", "#808080", "#C0C0C0", "#E8E8E8", "#FFFFFF")) {
			ColorName name = name(hex);
			assertThat(name.chroma()).as(hex + " chroma band").isEqualTo(Chroma.ACHROMATIC);
			assertThat(name.en()).as(hex + " en").doesNotContain("greyish").doesNotContain("vivid").doesNotContain("muted");
			assertThat(name.de()).as(hex + " de").doesNotContain("graustichig").doesNotContain("kräftig").doesNotContain("gedämpft");
		}
	}

	@Test
	public void testWellKnownColorsGetTheExpectedNames() {
		assertName("#FF0000", "red", "vivid red", "kräftiges Rot");
		assertName("#0000FF", "blue", "deep blue", "tiefes Blau");
		assertName("#00FF00", "green", "brilliant green", "strahlendes Grün");
		assertName("#FFFF00", "yellow", "brilliant yellow", "strahlendes Gelb");
		assertName("#000080", "blue", "very dark blue", "sehr dunkles Blau");
		assertName("#FFC0CB", "pink", "pale pink", "blasses Rosa");
		assertName("#8B4513", "brown", "dark brown", "dunkles Braun");
		assertName("#ADD8E6", "blue", "pale blue", "blasses Blau");
		assertName("#2F4F4F", "blue", "dark greyish blue", "dunkles graustichiges Blau");
		assertName("#F5F5DC", "yellow", "very pale yellow", "sehr blasses Gelb");
		assertName("#3B6EA5", "blue", "muted blue", "gedämpftes Blau");
		assertName("#800080", "purple", "dark purple", "dunkles Violett");
		assertName("#FFA500", "orange", "bright orange", "leuchtendes Orange");
		assertName("#A0522D", "brown", "brown", "Braun");
		assertName("#2E8B57", "green", "muted green", "gedämpftes Grün");
	}

	/**
	 * All 17 reachable cells of the 5x4 modifier table, in both languages. The expectation is
	 * built from the term the namer picked rather than a hard-coded one, so this pins the
	 * composition table without also pinning the prototype lookup.
	 */
	@Test
	public void testEveryModifierCellComposesInBothLanguages() {
		assertModifier(15, 30, -40, "very dark", "sehr dunkles");

		assertModifier(30, 15, 5, "dark greyish", "dunkles graustichiges");
		assertModifier(30, 30, 5, "dark", "dunkles");
		assertModifier(30, 50, 5, "dark", "dunkles");
		assertModifier(30, 70, 20, "deep", "tiefes");

		assertModifier(50, 15, 5, "greyish", "graustichiges");
		assertModifier(50, 30, 5, "muted", "gedämpftes");
		assertModifier(50, 50, 5, "", "");
		assertModifier(50, 70, 20, "vivid", "kräftiges");

		assertModifier(75, 15, 5, "pale", "blasses");
		assertModifier(75, 30, 5, "light", "helles");
		assertModifier(75, 50, 5, "light", "helles");
		assertModifier(75, 70, 20, "bright", "leuchtendes");

		assertModifier(90, 15, 5, "very pale", "sehr blasses");
		assertModifier(90, 30, 5, "very light", "sehr helles");
		assertModifier(90, 50, 5, "very light", "sehr helles");
		assertModifier(90, 70, 20, "brilliant", "strahlendes");
	}

	@Test
	public void testGermanNounsAreNeuterAndCapitalised() {
		for (Term term : ColorTerms.all()) {
			assertThat(term.de()).as(term.key()).matches("[A-ZÄÖÜ][a-zäöüß]+");
		}
	}

	@Test
	public void testCustomThresholdsAreHonoured() {
		Lab steel = ColorSpaces.rgbToLab(Rgb.ofHex("#3B6EA5"));
		assertThat(steel.chroma()).isCloseTo(34.66d, within(0.05d));

		// Raising the achromatic threshold above the colour's chroma pushes it onto the grey path.
		ColorName greyed = new ColorNamer(40.0d, 20.0d, 85.0d).name(steel);
		assertThat(greyed.term()).isEqualTo("grey");
		assertThat(greyed.chroma()).isEqualTo(Chroma.ACHROMATIC);

		// Raising blackLightness turns a mid grey black.
		Lab mid = ColorSpaces.rgbToLab(new Rgb(128, 128, 128));
		assertThat(new ColorNamer(12.0d, 60.0d, 85.0d).name(mid).term()).isEqualTo("black");

		// Lowering whiteLightness turns the same mid grey white.
		assertThat(new ColorNamer(12.0d, 20.0d, 50.0d).name(mid).term()).isEqualTo("white");
	}

	private ColorName name(String hex) {
		return namer.name(ColorSpaces.rgbToLab(Rgb.ofHex(hex)));
	}

	private void assertName(String hex, String term, String en, String de) {
		ColorName name = name(hex);
		assertThat(name.term()).as(hex + " term").isEqualTo(term);
		assertThat(name.en()).as(hex + " en").isEqualTo(en);
		assertThat(name.de()).as(hex + " de").isEqualTo(de);
	}

	private void assertModifier(double l, double a, double b, String prefixEn, String prefixDe) {
		ColorName name = namer.name(new Lab(l, a, b));
		Term term = ColorTerms.byKey(name.term());
		String label = "L*=" + l + " a*=" + a + " b*=" + b + " (" + name.lightness() + "/" + name.chroma() + ")";
		assertThat(name.en()).as(label + " en").isEqualTo(compose(prefixEn, term.en()));
		assertThat(name.de()).as(label + " de").isEqualTo(compose(prefixDe, term.de()));
	}

	private static String compose(String prefix, String noun) {
		return prefix.isEmpty() ? noun : prefix + " " + noun;
	}
}
