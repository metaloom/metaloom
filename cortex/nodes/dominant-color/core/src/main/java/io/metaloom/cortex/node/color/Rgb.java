package io.metaloom.cortex.node.color;

/**
 * An sRGB colour with 8-bit channels.
 *
 * @param r red channel, 0..255
 * @param g green channel, 0..255
 * @param b blue channel, 0..255
 */
public record Rgb(int r, int g, int b) {

	/**
	 * Build a colour from a packed ARGB or RGB int, discarding any alpha byte.
	 *
	 * @param argb packed value as returned by {@link java.awt.image.BufferedImage#getRGB(int, int)}
	 * @return the colour
	 */
	public static Rgb ofPacked(int argb) {
		return new Rgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
	}

	/**
	 * Parse a {@code #RRGGBB} or {@code RRGGBB} hex string.
	 *
	 * @param hex the hex string
	 * @return the colour
	 */
	public static Rgb ofHex(String hex) {
		String digits = hex.startsWith("#") ? hex.substring(1) : hex;
		if (digits.length() != 6) {
			throw new IllegalArgumentException("Expected a 6 digit hex colour but got '" + hex + "'");
		}
		return ofPacked(Integer.parseInt(digits, 16));
	}

	/**
	 * @return the colour packed into the low three bytes of an int, {@code 0xRRGGBB}
	 */
	public int packed() {
		return (r << 16) | (g << 8) | b;
	}

	/**
	 * @return the colour as an uppercase, zero-padded {@code #RRGGBB} string
	 */
	public String hex() {
		return String.format("#%02X%02X%02X", r, g, b);
	}
}
