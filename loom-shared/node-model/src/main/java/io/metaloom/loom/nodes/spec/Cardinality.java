package io.metaloom.loom.nodes.spec;

/**
 * How many elements a port carries.
 *
 * <p>
 * Cardinality lives on the port and <strong>never</strong> in the content type. The former model smuggled list-ness into the type vocabulary — a
 * script's {@code IMAGE_LIST} and {@code IMAGE} outputs both declared {@code data/thumbnail} — which made "one or many" invisible to the editor and to
 * the engine.
 * </p>
 */
public enum Cardinality {

	/** Exactly one element. */
	ONE,

	/**
	 * A sequence of elements. A {@code MANY} output is what makes the engine fan out: every downstream node with a {@code ONE} input bound to it runs
	 * once per element, and a downstream {@code MANY} input gathers the whole sequence back per origin item.
	 */
	MANY;

	public boolean isMany() {
		return this == MANY;
	}
}
