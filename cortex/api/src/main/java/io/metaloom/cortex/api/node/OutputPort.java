package io.metaloom.cortex.api.node;

import io.metaloom.loom.nodes.spec.Cardinality;

/**
 * A declared output of a node — the runtime half of the descriptor's {@code PortSpec}.
 *
 * <p>
 * Replaces {@code NodeOutputKey}, which carried a {@code valueType()} that the context threw away on the very next line and no content type at all.
 * An {@code OutputPort} carries both, and the boundary ({@code NodeResultMapper.toWire}) coerces against the content type on write — so a node that
 * emits the wrong shape fails <em>its own</em> task with a message naming the port, instead of blowing up in a downstream consumer or silently
 * clearing a persist batch.
 * </p>
 *
 * @param <T>
 *            the Java type a single element of this port carries
 */
public record OutputPort<T>(String id, String contentType, Cardinality cardinality, Class<T> valueType) {

	/**
	 * A port emitting exactly one element, written with {@code ctx.output(PORT, value)}.
	 */
	public static <T> OutputPort<T> one(String id, String contentType, Class<T> type) {
		return new OutputPort<>(id, contentType, Cardinality.ONE, type);
	}

	/**
	 * A port emitting a sequence, appended to with {@code ctx.outputElement(PORT, value)}. A {@code MANY} port is what makes the engine fan out: its
	 * element count becomes the number of downstream per-element tasks.
	 */
	public static <T> OutputPort<T> many(String id, String contentType, Class<T> type) {
		return new OutputPort<>(id, contentType, Cardinality.MANY, type);
	}

	public boolean isMany() {
		return cardinality != null && cardinality.isMany();
	}

	@Override
	public String toString() {
		return id + " : " + contentType + " " + cardinality;
	}
}
