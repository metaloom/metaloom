package io.metaloom.cortex.node.sam2;

/**
 * Which of SAM 2's three jobs this node instance does.
 *
 * <p>
 * An explicit option rather than something derived from which ports happen to be wired, for two
 * reasons found in the runtime. {@code NodeContext.create(media)} builds empty inputs, so
 * {@code ctx.isWired} is false for every docs fixture and every unit test — a wiring-derived mode
 * would document a different node from the one that runs. And wiring cannot separate
 * {@link #AUTOMATIC} on a video from {@link #TRACK}: both are "video in, no detections".
 * </p>
 *
 * <p>
 * Media <em>shape</em> is still read from {@code ctx.media()}, not from the mode. The mode says what
 * to do; the file says whether there are frames to do it over.
 * </p>
 */
public enum Sam2Mode {

	/**
	 * Segment everything. SAM 2 samples a grid of points over the frame and returns every mask it can
	 * find, unlabelled and unordered — so the node sorts them largest first, because a consumer that
	 * truncates should keep what a human would call the subject.
	 */
	AUTOMATIC,

	/**
	 * One mask per box an upstream detector found. The {@code detections} input is a {@code MANY} port,
	 * so the whole detector branch is gathered and every box goes through a single call — one image
	 * encode rather than one per object.
	 */
	PROMPTED,

	/**
	 * Follow masks through a clip. Prompts are placed on one sampled frame and SAM 2's memory
	 * attention propagates them across the rest. Video only: asked to track a still, the node fails
	 * rather than skipping, because it was given a job it cannot do.
	 */
	TRACK
}
