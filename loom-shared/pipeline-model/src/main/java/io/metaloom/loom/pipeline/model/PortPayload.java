package io.metaloom.loom.pipeline.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Everything one port carries for one dispatch: the declared type, the cardinality, and the
 * elements themselves.
 *
 * <p>
 * This replaces the untyped {@code Map<String,Object>} that outputs used to be. Carrying the
 * declared {@code contentType} and {@code cardinality} alongside the values is what lets the
 * receiving side coerce and validate without having to look the descriptor up again — and what lets
 * a gathered payload say "these twelve elements are all {@code text/plain}" rather than leaving the
 * consumer to infer it.
 * </p>
 */
public class PortPayload {

	private final String contentType;
	private final String cardinality;
	private final List<DataElement> elements;

	@JsonCreator
	public PortPayload(@JsonProperty("contentType") String contentType,
		@JsonProperty("cardinality") String cardinality,
		@JsonProperty("elements") List<DataElement> elements) {
		this.contentType = Objects.requireNonNull(contentType, "A payload content type must be set");
		this.cardinality = cardinality == null ? "ONE" : cardinality;
		this.elements = elements == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(elements));
	}

	/**
	 * A single-element payload.
	 */
	public static PortPayload one(String contentType, Origin origin, Object value) {
		return new PortPayload(contentType, "ONE", List.of(DataElement.of(origin, value)));
	}

	/**
	 * A sequence payload.
	 */
	public static PortPayload many(String contentType, List<DataElement> elements) {
		return new PortPayload(contentType, "MANY", elements);
	}

	public String getContentType() {
		return contentType;
	}

	/** @return {@code ONE} or {@code MANY} */
	public String getCardinality() {
		return cardinality;
	}

	public List<DataElement> getElements() {
		return elements;
	}

	@JsonIgnore
	public boolean isMany() {
		return "MANY".equals(cardinality);
	}

	@JsonIgnore
	public boolean isEmpty() {
		return elements.isEmpty();
	}

	@JsonIgnore
	public int size() {
		return elements.size();
	}

	/**
	 * The single value of a {@code ONE} payload.
	 *
	 * @return the value, or null when the payload is empty
	 */
	@JsonIgnore
	public Object single() {
		return elements.isEmpty() ? null : elements.get(0).getValue();
	}

	/**
	 * The element whose origin sequence index matches, used when zipping two per-element branches
	 * that descend from the same fan-out.
	 *
	 * @return the element, or null when the sequence has no such index
	 */
	@JsonIgnore
	public DataElement atSeq(int seq) {
		for (DataElement e : elements) {
			if (e.getOrigin().getSeq() == seq) {
				return e;
			}
		}
		return null;
	}

	/**
	 * The raw values, in sequence order.
	 */
	@JsonIgnore
	public List<Object> values() {
		return elements.stream().map(DataElement::getValue).toList();
	}

	@Override
	public String toString() {
		return "PortPayload[" + contentType + " " + cardinality + " x" + elements.size() + "]";
	}
}
