package io.metaloom.cortex.api.node;

import io.metaloom.loom.nodes.spec.Cardinality;

/**
 * A declared input of a node — the runtime half of the descriptor's {@code PortSpec}.
 *
 * <p>
 * This is the type that never existed before: a node read its upstream data through
 * {@code ctx.upstreamOutput(nodeId, key)}, keyed by a node id the <em>pipeline author</em> chose in the editor. Renaming a node silently returned
 * {@code null} and every consumer treated {@code null} as "absent", so four nodes hard-coded an id and one of them ({@code loom}) hard-coded ids that
 * no kind ever produced. A port id is owned by the node, so none of that is expressible any more: the engine resolves which upstream
 * {@code (node, port)} fills this port from the wired edges.
 * </p>
 *
 * <p>
 * Declare ports as public static constants so the cross-tree conformance test can reflect over them and hold them against the descriptor:
 * </p>
 *
 * <pre>{@code
 * public static final InputPort<String> IN_TEXT = InputPort.one("text", ContentTypeRegistry.TEXT_ANY, String.class);
 * }</pre>
 *
 * @param <T>
 *            the Java type a single element of this port carries
 */
public record InputPort<T>(String id, String contentType, Cardinality cardinality, Class<T> valueType) {

	/**
	 * A port carrying exactly one element, read with {@code ctx.input(PORT)}.
	 */
	public static <T> InputPort<T> one(String id, String contentType, Class<T> type) {
		return new InputPort<>(id, contentType, Cardinality.ONE, type);
	}

	/**
	 * A port carrying a sequence, read with {@code ctx.inputs(PORT)}. The elements arrive seq-ordered and origin-tagged — that ordering is what lets a
	 * consumer zip two branches of the same fan-out together.
	 */
	public static <T> InputPort<T> many(String id, String contentType, Class<T> type) {
		return new InputPort<>(id, contentType, Cardinality.MANY, type);
	}

	public boolean isMany() {
		return cardinality != null && cardinality.isMany();
	}

	@Override
	public String toString() {
		return id + " : " + contentType + " " + cardinality;
	}
}
