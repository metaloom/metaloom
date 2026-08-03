package io.metaloom.cortex.node.metadata;

import io.vertx.core.json.JsonObject;

/**
 * Serialisation helpers shared by the envelope blocks.
 *
 * <p>
 * They exist to enforce one rule from the envelope contract: <b>absent is not empty</b>. A field the
 * file did not carry is omitted; it is never {@code ""} and never {@code 0}. A reader can therefore
 * tell "this photo has no GPS" from "this photo is at the equator".
 * </p>
 */
final class Envelopes {

	private Envelopes() {
	}

	static void putIfPresent(JsonObject json, String key, String value) {
		if (value != null && !value.isBlank()) {
			json.put(key, value);
		}
	}

	static void putIfPresent(JsonObject json, String key, Number value) {
		if (value != null) {
			json.put(key, value);
		}
	}

	static void putIfPresent(JsonObject json, String key, Boolean value) {
		if (value != null) {
			json.put(key, value);
		}
	}

	static void putIfPresent(JsonObject json, String key, JsonObject value) {
		if (value != null && !value.isEmpty()) {
			json.put(key, value);
		}
	}
}
