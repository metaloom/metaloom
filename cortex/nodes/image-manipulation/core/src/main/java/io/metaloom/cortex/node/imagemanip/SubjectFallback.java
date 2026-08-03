package io.metaloom.cortex.node.imagemanip;

/** What {@link Op#SUBJECT_CROP} does for an image where no detection survived filtering. */
public enum SubjectFallback {

	/**
	 * Frame the centre instead, i.e. behave as {@link Op#ASPECT} would.
	 *
	 * <p>
	 * The default, because an image with no faces in it is the ordinary case rather than an error - a landscape in a photo library is not a failed
	 * subject crop.
	 * </p>
	 */
	CENTRE,

	/** Skip the item. Its outputs survive, so a downstream node still sees whatever earlier ports carried. */
	SKIP,

	/** Fail the item. For a pipeline where an image without a subject genuinely is a defect worth surfacing. */
	FAIL
}
