package io.metaloom.cortex.node.captioning;

/**
 * Outcome of a single vision-model captioning call: the produced text plus the wall-clock latency of the model round-trip and the number of
 * frames/inputs that were sent. Used both as the node output payload and by the comparison harness to score latency.
 */
public record CaptionResult(String text, long latencyMs, int inputCount) {
}
