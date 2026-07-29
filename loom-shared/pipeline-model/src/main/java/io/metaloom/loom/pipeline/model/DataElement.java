package io.metaloom.loom.pipeline.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One value flowing between nodes, tagged with where it came from.
 *
 * <p>
 * The {@code value} is a JSON-native tree — {@code String}, {@code Long}, {@code Double},
 * {@code Boolean}, {@code Map} or {@code List}. Structured data stays JSON rather than becoming a
 * POJO, because the value has to survive Jackson, a JSONB column and a stringifying disk cache
 * without the engine knowing anything about its shape.
 * </p>
 */
public class DataElement {

	private final Origin origin;
	private final Object value;

	@JsonCreator
	public DataElement(@JsonProperty("origin") Origin origin, @JsonProperty("value") Object value) {
		this.origin = Objects.requireNonNull(origin, "An element origin must be set");
		this.value = value;
	}

	public static DataElement of(Origin origin, Object value) {
		return new DataElement(origin, value);
	}

	public Origin getOrigin() {
		return origin;
	}

	public Object getValue() {
		return value;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof DataElement other)) {
			return false;
		}
		return origin.equals(other.origin) && Objects.equals(value, other.value);
	}

	@Override
	public int hashCode() {
		return Objects.hash(origin, value);
	}

	@Override
	public String toString() {
		return "DataElement[" + origin + " = " + value + "]";
	}
}
