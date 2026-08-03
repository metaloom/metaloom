package io.metaloom.cortex.node.imagemanip;

/** What fills the margins {@link AspectMode#PAD} creates. */
public enum PadFill {

	/** A flat colour - the classic black bar. */
	COLOR,

	/**
	 * A blurred, scaled copy of the image itself.
	 *
	 * <p>
	 * <strong>This is the vertical-video-syndrome fix.</strong> A portrait frame padded into a landscape target gets margins that belong to the picture
	 * instead of two black bars.
	 * </p>
	 */
	BLUR
}
