package io.metaloom.cortex.api.node;

import java.util.ArrayList;
import java.util.List;

import io.metaloom.loom.nodes.spec.ValueCoercer;
import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;

/**
 * What one output port accumulated during a single node execution.
 *
 * <p>
 * The declaring {@link OutputPort} travels with the values on purpose. The boundary that turns this into the wire {@code PortPayload} needs the
 * content type to coerce against and the cardinality to decide whether to stamp one origin or a numbered sequence — and looking either of them up
 * again from a descriptor is exactly the cross-tree lookup that let {@code llm_result} and {@code md5sum} drift in the first place.
 * </p>
 *
 * <p>
 * A {@code ONE} port holds a single value; a {@code MANY} port holds them in emission order, which is the order the engine numbers them in.
 * </p>
 */
public record PortOutput(OutputPort<?> port, List<Object> values) {

	/** A {@code ONE} port's single value. */
	public static <T> PortOutput one(OutputPort<T> port, T value) {
		return new PortOutput(port, List.of(value));
	}

	/** A {@code MANY} port's elements, in emission order. */
	public static <T> PortOutput many(OutputPort<T> port, List<T> values) {
		return new PortOutput(port, List.copyOf(values));
	}

	/**
	 * @return the single value, or null when nothing was emitted
	 */
	public Object single() {
		return values.isEmpty() ? null : values.get(0);
	}

	/**
	 * Turn this accumulator into the wire payload, coercing every value against the port's declared
	 * content type and stamping each element's origin.
	 *
	 * <p>
	 * A {@code ONE} port yields a single element carrying this execution's origin — which for a
	 * per-element node is its own {@code elementSeq}, so a downstream zip can line the branches up
	 * again. A {@code MANY} port numbers its elements {@code 0..n-1} with {@code total = n}: that
	 * count is what the engine reads to decide how many per-element tasks to spawn downstream.
	 * </p>
	 *
	 * @param itemId     the run item every element descends from
	 * @param elementSeq which element of a fanned-out sequence produced this result
	 * @return the payload, or null when a {@code ONE} port emitted nothing
	 * @throws io.metaloom.loom.nodes.spec.ValueCoercionException
	 *             when a value cannot satisfy the declared type
	 */
	public PortPayload toPayload(String itemId, int elementSeq) {
		if (port.isMany()) {
			int total = values.size();
			List<DataElement> elements = new ArrayList<>(total);
			for (int seq = 0; seq < total; seq++) {
				elements.add(DataElement.of(Origin.of(itemId, seq, total), coerce(values.get(seq))));
			}
			return PortPayload.many(port.contentType(), elements);
		}
		if (values.isEmpty()) {
			return null;
		}
		return PortPayload.one(port.contentType(), new Origin(itemId, elementSeq, 1), coerce(values.get(0)));
	}

	private Object coerce(Object value) {
		return ValueCoercer.coerce(port.id(), port.contentType(), value);
	}

	public int size() {
		return values.size();
	}

	@Override
	public String toString() {
		return port.id() + "=" + (port.isMany() ? values.toString() : String.valueOf(single()));
	}
}
