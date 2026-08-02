package io.metaloom.cortex.node.filter;

/**
 * What a {@link FilterNode} instance matches its buckets against.
 *
 * <p>
 * This is the seam that lets one node kind replace the eight {@code filter-*} kinds that used to be
 * advertised. Each constant is backed by a {@link FilterStrategy}; adding one is a strategy class
 * plus a Dagger binding plus a value in the descriptor's {@code filterBy} parameter, and never an
 * edit to {@link FilterNode}.
 * </p>
 */
public enum FilterBy {

	/**
	 * The language of the wired text, decided by a language model.
	 *
	 * <p>
	 * A model rather than a detector library is a deliberate starting point, not a conclusion: it
	 * costs one round trip per item and needs a reachable Ollama. See the node's website page for
	 * the alternatives that were considered.
	 * </p>
	 */
	LANGUAGE
}
