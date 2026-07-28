package io.metaloom.cortex.node.color;

import io.metaloom.cortex.node.color.ColorTerms.Prototype;
import io.metaloom.cortex.node.color.ColorTerms.Term;

/**
 * Turns a CIELAB colour into a human-readable name in English and German.
 *
 * <p>
 * The scheme is ISCC-NBS in shape: a <em>basic colour term</em> chosen by nearest prototype, plus a
 * <em>modifier</em> composed from the colour's lightness and chroma bands. Both languages are
 * generated from the same two bands, so they can never drift apart.
 * </p>
 *
 * <h2>The achromatic gate</h2>
 *
 * Below {@code achromaticChroma} the hue angle carries no information, so the namer returns black,
 * grey or white on lightness alone and <strong>never consults a prototype or composes a chroma
 * modifier</strong>. That is what makes {@code greyish grey} impossible by construction rather than
 * by luck.
 *
 * <h2>German</h2>
 *
 * Every German term noun is neuter and capitalised, so the strong-declension {@code -es} adjective
 * ending is correct for all eleven with no article and no gender table. {@code Orange} and
 * {@code Rosa} are indeclinable as German <em>adjectives</em>, which is exactly why the
 * noun-plus-adjective construction is used instead: as nouns they are entirely regular.
 */
public final class ColorNamer {

	/** Default chroma below which a colour is named on lightness alone. */
	public static final double DEFAULT_ACHROMATIC_CHROMA = 12.0d;

	/** Default lightness below which an achromatic colour is black. */
	public static final double DEFAULT_BLACK_LIGHTNESS = 20.0d;

	/** Default lightness at or above which an achromatic colour is white. */
	public static final double DEFAULT_WHITE_LIGHTNESS = 85.0d;

	private final double achromaticChroma;

	private final double blackLightness;

	private final double whiteLightness;

	/**
	 * @param achromaticChroma chroma below which hue is ignored
	 * @param blackLightness   lightness below which an achromatic colour is black
	 * @param whiteLightness   lightness at or above which an achromatic colour is white
	 */
	public ColorNamer(double achromaticChroma, double blackLightness, double whiteLightness) {
		this.achromaticChroma = achromaticChroma;
		this.blackLightness = blackLightness;
		this.whiteLightness = whiteLightness;
	}

	/**
	 * @return a namer with the default thresholds
	 */
	public static ColorNamer defaults() {
		return new ColorNamer(DEFAULT_ACHROMATIC_CHROMA, DEFAULT_BLACK_LIGHTNESS, DEFAULT_WHITE_LIGHTNESS);
	}

	/**
	 * @param lab the colour
	 * @return its name
	 */
	public ColorName name(Lab lab) {
		double chroma = lab.chroma();
		Lightness lightness = Lightness.of(lab.l());

		if (chroma < achromaticChroma) {
			return achromatic(lab.l(), lightness);
		}

		Prototype nearest = null;
		double best = Double.MAX_VALUE;
		for (Prototype prototype : ColorTerms.chromaticPrototypes()) {
			double distance = ColorDistance.ciede2000(lab, prototype.lab());
			if (distance < best) {
				best = distance;
				nearest = prototype;
			}
		}

		Term term = ColorTerms.byKey(nearest.termKey());
		Chroma chromaBand = Chroma.of(chroma, achromaticChroma);
		String en = compose(prefixEn(lightness, chromaBand), term.en());
		String de = compose(prefixDe(lightness, chromaBand), term.de());
		return new ColorName(term.key(), lightness, chromaBand, en, de, best);
	}

	private ColorName achromatic(double l, Lightness lightness) {
		if (l < blackLightness) {
			return new ColorName(ColorTerms.BLACK.key(), lightness, Chroma.ACHROMATIC,
				ColorTerms.BLACK.en(), ColorTerms.BLACK.de(), 0d);
		}
		if (l >= whiteLightness) {
			return new ColorName(ColorTerms.WHITE.key(), lightness, Chroma.ACHROMATIC,
				ColorTerms.WHITE.en(), ColorTerms.WHITE.de(), 0d);
		}
		String en;
		String de;
		if (l < 35) {
			en = "dark grey";
			de = "dunkles Grau";
		} else if (l < 65) {
			en = "grey";
			de = "Grau";
		} else {
			en = "light grey";
			de = "helles Grau";
		}
		return new ColorName(ColorTerms.GREY.key(), lightness, Chroma.ACHROMATIC, en, de, 0d);
	}

	/**
	 * The English half of the 5x4 modifier table.
	 *
	 * <p>
	 * At {@link Lightness#VERY_DARK} the chroma band is deliberately ignored: below L* 20 chroma is
	 * barely perceptible and "very dark vivid blue" is not a phrase anyone uses.
	 * </p>
	 */
	private static String prefixEn(Lightness lightness, Chroma chroma) {
		return switch (lightness) {
			case VERY_DARK -> "very dark";
			case DARK -> switch (chroma) {
				case GREYISH -> "dark greyish";
				case VIVID -> "deep";
				default -> "dark";
			};
			case MEDIUM -> switch (chroma) {
				case GREYISH -> "greyish";
				case MUTED -> "muted";
				case VIVID -> "vivid";
				default -> "";
			};
			case LIGHT -> switch (chroma) {
				case GREYISH -> "pale";
				case VIVID -> "bright";
				default -> "light";
			};
			case VERY_LIGHT -> switch (chroma) {
				case GREYISH -> "very pale";
				case VIVID -> "brilliant";
				default -> "very light";
			};
		};
	}

	/** The German half of the same table. */
	private static String prefixDe(Lightness lightness, Chroma chroma) {
		return switch (lightness) {
			case VERY_DARK -> "sehr dunkles";
			case DARK -> switch (chroma) {
				case GREYISH -> "dunkles graustichiges";
				case VIVID -> "tiefes";
				default -> "dunkles";
			};
			case MEDIUM -> switch (chroma) {
				case GREYISH -> "graustichiges";
				case MUTED -> "gedämpftes";
				case VIVID -> "kräftiges";
				default -> "";
			};
			case LIGHT -> switch (chroma) {
				case GREYISH -> "blasses";
				case VIVID -> "leuchtendes";
				default -> "helles";
			};
			case VERY_LIGHT -> switch (chroma) {
				case GREYISH -> "sehr blasses";
				case VIVID -> "strahlendes";
				default -> "sehr helles";
			};
		};
	}

	private static String compose(String prefix, String noun) {
		return prefix.isEmpty() ? noun : prefix + " " + noun;
	}
}
