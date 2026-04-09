package io.metaloom.cortex.api.node.payload;

/**
 * Payload carrying arbitrary JSON data. Useful for unstructured or schema-flexible
 * data exchange between nodes. Prefer more specific payload types when the structure
 * is known.
 */
public interface JsonPayload extends Payload {

	/**
	 * The JSON string.
	 */
	String json();

	static JsonPayload of(String json) {
		return () -> json;
	}
}
