package io.metaloom.cortex.node.imagemanip;

/** How {@link Op#ASPECT} reaches the target ratio. */
public enum AspectMode {

	/** Cut the long axis. The result has no margins and loses pixels. */
	CROP,

	/** Grow the short axis into margins that {@link PadFill} then fills. The result keeps every pixel. */
	PAD
}
