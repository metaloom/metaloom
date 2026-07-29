package io.metaloom.loom.pipeline.graph;

/**
 * How often a node runs for one media item.
 *
 * <p>
 * Computed statically by the graph parser from the wired ports, so the engine never has to work it
 * out at dispatch time.
 * </p>
 */
public enum ExecutionMode {

	/**
	 * Once per item. This covers ordinary nodes <em>and</em> gather nodes — a node whose
	 * {@code MANY} input consumes a fanned-out sequence still runs once, receiving the whole
	 * sequence at once. That is what makes the recombination implicit: it is not a special node
	 * type, just a node whose dependencies happen to have settled into more than one element.
	 */
	SINGLE,

	/**
	 * Once per element of the sequence it consumes. A node lands here when one of its
	 * {@code ONE}-cardinality input ports is wired to an effectively-{@code MANY} output.
	 */
	PER_ELEMENT;

	public boolean isPerElement() {
		return this == PER_ELEMENT;
	}
}
