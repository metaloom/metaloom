package io.metaloom.cortex.node.guard;

import java.util.List;

/**
 * What one {@link GuardProbe} came back with, already normalised.
 *
 * <p>
 * {@code score} is separate from {@code hits} rather than derived from them because a clean probe
 * has a score and no hits: Llama Guard answering {@code safe} still tells us <em>how</em> safe, and
 * that number has to survive into the verdict's overall score. Deriving the score from an empty hit
 * list would silently turn every clean-but-borderline item into a confident 0.
 * </p>
 *
 * @param score      P(unsafe) for this probe, in {@code [0,1]}
 * @param scoreExact false when the backend returned no log probabilities and {@code score} is the
 *                   1/0 argmax fallback rather than a real probability
 * @param hits       the categories this probe named; empty when it came back clean
 * @param raw        the model's own output, kept for the payload so a surprising verdict is debuggable
 */
public record GuardProbeResult(double score, boolean scoreExact, List<GuardVerdict.Hit> hits, String raw) {
}
