package io.metaloom.cortex.api.node;

import io.metaloom.loom.pipeline.model.Origin;

/**
 * One element of a {@code MANY} input, with the origin tag it arrived under.
 *
 * <p>
 * The origin is kept rather than flattened away because it is the only thing that lets a gathering node put two fanned-out branches back together: the
 * elements of {@code summaries} and {@code sentiments} correspond when their {@link Origin#getSeq()} match. A node that only needs the values can
 * ignore it.
 * </p>
 *
 * @param <T>
 *            the value type declared by the port
 */
public record Element<T>(Origin origin, T value) {

	/**
	 * @return this element's index within the sequence it belongs to
	 */
	public int seq() {
		return origin.getSeq();
	}
}
