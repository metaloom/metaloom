package io.metaloom.cortex.node.color;

/**
 * A named colour: the machine-stable basic term, the two modifier bands it was derived from, and
 * the composed human-readable strings.
 *
 * @param term      the basic colour term key, e.g. {@code blue}. This is the facet key - it is
 *                  stable across a retune of the modifier bands, which {@link #en} and {@link #de}
 *                  are not
 * @param lightness the lightness band
 * @param chroma    the chroma band, or {@link Chroma#ACHROMATIC} when hue was never consulted
 * @param en        the English name, e.g. {@code dark greyish blue}
 * @param de        the German name, e.g. {@code dunkles graustichiges Blau}
 * @param distance  CIEDE2000 distance to the nearest prototype; 0 for an achromatic colour, which
 *                  is decided by threshold rather than by a lookup
 */
public record ColorName(String term, Lightness lightness, Chroma chroma, String en, String de, double distance) {
}
