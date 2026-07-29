package io.metaloom.loom.pipeline.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;

/**
 * Builders for the typed port payloads a worker would hand back.
 *
 * <p>Outputs stopped being a {@code Map<String,Object>} when ports gained types and
 * cardinality, so almost every engine test needs to fabricate one. Doing it inline made the
 * tests about payload construction rather than about the engine; this keeps the noise in one
 * place and, more importantly, makes the origin tags consistent — a test that hand-rolled its
 * own {@link Origin} could accidentally assert a gather works while feeding it elements no real
 * fan-out would ever produce.</p>
 */
public final class Payloads {

	/** Stand-in item id for tests that do not care whose origin an element carries. */
	public static final String ANY_ITEM = "item";

	private Payloads() {
	}

	/**
	 * A single-element payload, as a node that ran once for the whole item emits.
	 */
	public static PortPayload payload(String contentType, Object value) {
		return payload(ANY_ITEM, contentType, value);
	}

	public static PortPayload payload(String itemId, String contentType, Object value) {
		return PortPayload.one(contentType, Origin.single(itemId), value);
	}

	/**
	 * A sequence payload — the fan-out producer's side of the story.
	 *
	 * <p>Every element is tagged {@code origin{itemId, seq, total}} exactly as the engine expects
	 * to find it, because that tag is what a downstream {@code ONE} input matches on.</p>
	 */
	public static PortPayload sequence(String itemId, String contentType, Object... values) {
		List<DataElement> elements = new ArrayList<>();
		for (int seq = 0; seq < values.length; seq++) {
			elements.add(DataElement.of(Origin.of(itemId, seq, values.length), values[seq]));
		}
		return PortPayload.many(contentType, elements);
	}

	/**
	 * What one execution of a per-element node emits: a single element that keeps the sequence
	 * index it was dispatched for, so a later gather can order the branch again.
	 */
	public static PortPayload element(String itemId, int seq, int total, String contentType, Object value) {
		return new PortPayload(contentType, "ONE",
			List.of(DataElement.of(Origin.of(itemId, seq, total), value)));
	}

	/** One output port's payload, ready to hand to a {@code NodeTaskResult}. */
	public static Map<String, PortPayload> outputs(String portId, PortPayload payload) {
		Map<String, PortPayload> outputs = new LinkedHashMap<>();
		outputs.put(portId, payload);
		return outputs;
	}

	/** One output port carrying one value of the given type. */
	public static Map<String, PortPayload> outputs(String portId, String contentType, Object value) {
		return outputs(portId, payload(contentType, value));
	}
}
