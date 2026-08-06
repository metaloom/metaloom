package io.metaloom.cortex.node.sam2.video;

import java.util.List;

/**
 * What a sampling pass produced, and everything a caller needs to line the results back up against
 * the file.
 *
 * <p>
 * {@link #frameNumbers()} carries <strong>source</strong> frame numbers, not 0..N-1. That is the
 * whole reason this record exists rather than a bare list: a mask on sampled entry 3 means nothing,
 * a mask on source frame 75 can be seeked to.
 * </p>
 *
 * <p>
 * Three sizes travel together for the same reason {@code DepthmapNode} reports two dimension pairs —
 * upstream boxes were measured against the native frame, the sidecar answers in the sampled frame's
 * space, and projecting between them without both is a guess.
 * </p>
 *
 * @param jpegB64      the sampled frames as base64 JPEGs, in order
 * @param frameNumbers the source frame number of each entry, same length as {@code jpegB64}
 * @param nativeWidth  width of the video's own frames
 * @param nativeHeight height of the video's own frames
 * @param sampledWidth width of the frames actually encoded
 * @param sampledHeight height of the frames actually encoded
 * @param capped       whether {@code maxFrames} stopped the walk before the end of the file
 */
public record SampledFrames(
	List<String> jpegB64,
	List<Integer> frameNumbers,
	int nativeWidth,
	int nativeHeight,
	int sampledWidth,
	int sampledHeight,
	boolean capped) {

	public boolean isEmpty() {
		return jpegB64.isEmpty();
	}

	public int size() {
		return jpegB64.size();
	}

	/**
	 * Factor to multiply a native-space coordinate by to reach sampled space.
	 *
	 * <p>
	 * Derived from the frames that were actually encoded rather than from the requested
	 * {@code maxDim}, because the rounding in the resize means the two are not always the same number
	 * — the same care {@code VideoObjectScanner} takes going the other way.
	 * </p>
	 */
	public double scaleToSampled() {
		return nativeWidth <= 0 ? 1.0d : (double) sampledWidth / (double) nativeWidth;
	}

	/**
	 * The sampled entry whose source frame is nearest to {@code sourceFrame}.
	 *
	 * <p>
	 * An upstream detector samples at <em>its</em> chop rate, so a box stamped frame 137 need not be
	 * one of the frames sampled here. Snapping is the honest answer: dropping the box silently loses
	 * an object, and forcing the two chop rates to agree would couple this node to the id of whatever
	 * produced the boxes — the shape {@code NODES.md} §6.4 spent a release deleting.
	 * </p>
	 *
	 * @param sourceFrame the frame an upstream element named
	 * @return the index into this sequence, or -1 when nothing was sampled
	 */
	public int nearestIndex(int sourceFrame) {
		int best = -1;
		int bestDistance = Integer.MAX_VALUE;
		for (int i = 0; i < frameNumbers.size(); i++) {
			int distance = Math.abs(frameNumbers.get(i) - sourceFrame);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}
}
