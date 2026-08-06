package io.metaloom.cortex.node.sam2;

/**
 * One object to follow through a clip: which box, and which of the sampled frames it sits on.
 *
 * <p>
 * {@code frameIndex} is an index into the sampled sequence, <strong>not</strong> a source frame
 * number. The node owns that mapping because the node owns the chop rate; the sidecar only ever sees
 * a list of frames it was handed. The source numbers travel separately so they can be echoed back
 * onto each result without the sidecar having to reconstruct them.
 * </p>
 *
 * @param objId      the tracking id, stable across every frame of the propagation
 * @param frameIndex index into the sampled sequence where the prompt is placed
 * @param box        the prompt box, already in the posted frames' pixel space
 */
public record Sam2TrackPrompt(int objId, int frameIndex, Sam2Box box) {
}
