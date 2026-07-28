package io.metaloom.cortex.node.color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bilingual basic-colour-term vocabulary and its CIELAB codebook.
 *
 * <h2>Why eleven terms</h2>
 *
 * The eleven are the Berlin &amp; Kay basic colour terms - the set that every fully evolved colour
 * vocabulary converges on, and the set that survives translation. Restricting the vocabulary to
 * them is what lets one table serve English and German without a per-language dictionary: the
 * eleven have exact one-word equivalents in both, which "teal", "mauve" or "petrol" do not.
 *
 * <h2>Why several prototypes per term rather than one anchor</h2>
 *
 * A single Lab anchor per term is the obvious design and it is wrong. Nearest-CIEDE2000 against one
 * anchor counts lightness twice - once when choosing the term, once again when the modifier is
 * composed - so dark and light members of a hue drift onto neighbouring terms. Measured against a
 * single-anchor table, {@code #000080} navy and {@code #191970} midnightblue both come out
 * <em>purple</em>, and {@code #00FF00} comes out <em>yellow</em> by a tenth of a delta-E.
 *
 * <p>
 * Giving each chromatic term a handful of prototypes spread along its own lightness range fixes
 * that without changing the algorithm: the term of the nearest prototype wins. The first entry of
 * each row is the ISCC-NBS / Kelly centroid for that term; the rest span the range.
 * </p>
 *
 * <h2>Why the table is Java and not a resource</h2>
 *
 * It is about sixty strings, and it is keyed on {@link Lightness} and {@link Chroma}, so it can
 * never be edited independently of them - the usual argument for externalising a table does not
 * apply. In Java it is compile-checked, javadoc-able, directly assertable from a test, and it
 * cannot acquire the failure mode a bundled resource would: an encoding slip that silently turns
 * {@code Weiß} into a replacement character on a platform whose default charset is not UTF-8.
 */
public final class ColorTerms {

	/**
	 * A basic colour term and its two names.
	 *
	 * @param key the machine-stable key, e.g. {@code blue}
	 * @param en  the English noun
	 * @param de  the German noun. Always neuter and capitalised, which is what makes the strong
	 *            declension {@code -es} adjective in {@link ColorNamer} correct for every term with
	 *            no article and no gender lookup
	 */
	public record Term(String key, String en, String de) {
	}

	/**
	 * One entry of the CIELAB codebook.
	 *
	 * @param termKey the term this prototype votes for
	 * @param hex     the prototype colour, kept as hex so it is auditable by eye
	 * @param lab     the same colour converted once, at class-init
	 */
	public record Prototype(String termKey, String hex, Lab lab) {
	}

	public static final Term RED = new Term("red", "red", "Rot");
	public static final Term ORANGE = new Term("orange", "orange", "Orange");
	public static final Term YELLOW = new Term("yellow", "yellow", "Gelb");
	public static final Term GREEN = new Term("green", "green", "Grün");
	public static final Term BLUE = new Term("blue", "blue", "Blau");
	public static final Term PURPLE = new Term("purple", "purple", "Violett");
	public static final Term PINK = new Term("pink", "pink", "Rosa");
	public static final Term BROWN = new Term("brown", "brown", "Braun");
	public static final Term BLACK = new Term("black", "black", "Schwarz");
	public static final Term GREY = new Term("grey", "grey", "Grau");
	public static final Term WHITE = new Term("white", "white", "Weiß");

	private static final Map<String, Term> BY_KEY = new LinkedHashMap<>();

	private static final List<Prototype> CHROMATIC_PROTOTYPES;

	static {
		for (Term term : List.of(RED, ORANGE, YELLOW, GREEN, BLUE, PURPLE, PINK, BROWN, BLACK, GREY, WHITE)) {
			BY_KEY.put(term.key(), term);
		}

		List<Prototype> prototypes = new ArrayList<>();
		addAll(prototypes, RED, "#BE0032", "#FF0000", "#8B0000", "#E34234", "#CD5C5C");
		addAll(prototypes, ORANGE, "#F38400", "#FF8C00", "#E25822", "#FFA500");
		addAll(prototypes, YELLOW, "#F3C300", "#FFFF00", "#FFD700", "#DCD300");
		addAll(prototypes, GREEN, "#008856", "#00FF00", "#2E8B57", "#006400", "#8DB600", "#40826D");
		addAll(prototypes, BLUE, "#0067A5", "#0000FF", "#000080", "#4169E1", "#00BFFF", "#87CEEB", "#00FFFF", "#008080");
		addAll(prototypes, PURPLE, "#875692", "#800080", "#4B0082", "#9370DB", "#604E97", "#8A2BE2", "#DA70D6");
		addAll(prototypes, PINK, "#E68FAC", "#FFC0CB", "#FF69B4", "#F99379", "#FF00FF");
		addAll(prototypes, BROWN, "#7E4B2A", "#8B4513", "#654522", "#A0522D", "#C19A6B", "#882D17");
		CHROMATIC_PROTOTYPES = List.copyOf(prototypes);
	}

	private ColorTerms() {
	}

	/**
	 * The CIELAB codebook for the eight chromatic terms. The three achromatic terms
	 * ({@code black}, {@code grey}, {@code white}) are deliberately absent: they are decided by a
	 * chroma threshold in {@link ColorNamer}, never by a nearest-prototype lookup, because a hue
	 * angle is meaningless for them.
	 *
	 * @return the prototypes, in term order
	 */
	public static List<Prototype> chromaticPrototypes() {
		return CHROMATIC_PROTOTYPES;
	}

	/**
	 * @param key a term key
	 * @return the term
	 * @throws IllegalArgumentException when the key is not one of the eleven
	 */
	public static Term byKey(String key) {
		Term term = BY_KEY.get(key);
		if (term == null) {
			throw new IllegalArgumentException("Unknown colour term '" + key + "'");
		}
		return term;
	}

	/**
	 * @return all eleven terms, chromatic first
	 */
	public static List<Term> all() {
		return List.copyOf(BY_KEY.values());
	}

	private static void addAll(List<Prototype> target, Term term, String... hexes) {
		for (String hex : hexes) {
			target.add(new Prototype(term.key(), hex, ColorSpaces.rgbToLab(Rgb.ofHex(hex))));
		}
	}
}
