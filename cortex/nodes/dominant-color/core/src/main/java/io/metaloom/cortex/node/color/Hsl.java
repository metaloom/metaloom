package io.metaloom.cortex.node.color;

/**
 * A colour in HSL. Emitted purely for consumers - CSS, design tools and colour pickers speak HSL,
 * and no computation in this node uses it.
 *
 * @param h hue in degrees, 0..360
 * @param s saturation in percent, 0..100
 * @param l lightness in percent, 0..100
 */
public record Hsl(double h, double s, double l) {
}
