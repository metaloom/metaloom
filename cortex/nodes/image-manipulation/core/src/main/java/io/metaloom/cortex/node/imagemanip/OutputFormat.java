package io.metaloom.cortex.node.imagemanip;

/**
 * The encodings the node can write.
 *
 * <p>
 * Two, deliberately. WebP and AVIF would both be better than either of these for a derived rendition, and neither ships with the JDK's ImageIO - adding
 * an encoder dependency to get them is a decision for whoever needs them, not a side effect of this node.
 * </p>
 */
public enum OutputFormat {

	/** Lossless, alpha-capable. Right for graphics, screenshots and anything that will be transformed again. */
	PNG("png", ".png", "image/png", true),

	/**
	 * Lossy, no alpha. Right for photographs, which is what most of this node's input is.
	 *
	 * <p>
	 * 🔴 Because it cannot hold alpha, an image with a transparent channel must be flattened before encoding - handing a {@code TYPE_INT_ARGB} raster
	 * to the JPEG writer produces inverted or pink output on several JDKs rather than an error. See {@link ManipulationImages#flatten}.
	 * </p>
	 */
	JPEG("jpeg", ".jpg", "image/jpeg", false);

	private final String writerFormat;

	private final String extension;

	private final String mimeType;

	private final boolean supportsAlpha;

	OutputFormat(String writerFormat, String extension, String mimeType, boolean supportsAlpha) {
		this.writerFormat = writerFormat;
		this.extension = extension;
		this.mimeType = mimeType;
		this.supportsAlpha = supportsAlpha;
	}

	/** The informal format name ImageIO looks writers up by. */
	public String writerFormat() {
		return writerFormat;
	}

	/** The artifact file extension, leading dot included. */
	public String extension() {
		return extension;
	}

	/** The MIME type, for a future upload path. */
	public String mimeType() {
		return mimeType;
	}

	/** Whether the encoding carries an alpha channel. */
	public boolean supportsAlpha() {
		return supportsAlpha;
	}
}
