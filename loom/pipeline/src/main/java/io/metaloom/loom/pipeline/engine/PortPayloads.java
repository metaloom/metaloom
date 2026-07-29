package io.metaloom.loom.pipeline.engine;

import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Converts port payloads to and from plain JSON for storage.
 *
 * <p>
 * The engine persists a node's outputs into a JSONB column, and Vert.x's {@code JsonObject} only
 * accepts JSON-native values — it cannot wrap a map of POJOs. Rather than letting each call site
 * improvise, the encoding lives here so the stored shape has exactly one definition and a recovered
 * run reads back what a live run wrote.
 * </p>
 *
 * <p>
 * Encoding is total and lossless for anything the coercer admits. Decoding is deliberately
 * <strong>lenient</strong>: a row written before this shape existed, or one hand-edited into
 * nonsense, yields an empty payload map rather than throwing — losing a cached result is an
 * inconvenience, failing recovery over it is an outage.
 * </p>
 */
public final class PortPayloads {

	private PortPayloads() {
	}

	/**
	 * Encode a node's outputs for the {@code pipeline_node_task.outputs} column.
	 */
	public static JsonObject encode(Map<String, PortPayload> outputs) {
		JsonObject json = new JsonObject();
		if (outputs == null) {
			return json;
		}
		for (Map.Entry<String, PortPayload> entry : outputs.entrySet()) {
			PortPayload payload = entry.getValue();
			if (payload == null) {
				continue;
			}
			JsonArray elements = new JsonArray();
			for (DataElement element : payload.getElements()) {
				Origin origin = element.getOrigin();
				elements.add(new JsonObject()
					.put("origin", new JsonObject()
						.put("itemId", origin.getItemId())
						.put("seq", origin.getSeq())
						.put("total", origin.getTotal()))
					.put("value", element.getValue()));
			}
			json.put(entry.getKey(), new JsonObject()
				.put("contentType", payload.getContentType())
				.put("cardinality", payload.getCardinality())
				.put("elements", elements));
		}
		return json;
	}

	/**
	 * Decode a stored outputs column.
	 *
	 * @return the payloads, never null; empty when the column is null, empty or unreadable
	 */
	public static Map<String, PortPayload> decode(JsonObject json) {
		Map<String, PortPayload> outputs = new LinkedHashMap<>();
		if (json == null) {
			return outputs;
		}
		for (String portId : json.fieldNames()) {
			try {
				JsonObject payload = json.getJsonObject(portId);
				if (payload == null) {
					continue;
				}
				String contentType = payload.getString("contentType");
				if (contentType == null) {
					continue;
				}
				String cardinality = payload.getString("cardinality", "ONE");
				List<DataElement> elements = new ArrayList<>();
				JsonArray stored = payload.getJsonArray("elements");
				if (stored != null) {
					for (int i = 0; i < stored.size(); i++) {
						JsonObject element = stored.getJsonObject(i);
						if (element == null) {
							continue;
						}
						JsonObject origin = element.getJsonObject("origin");
						if (origin == null || origin.getString("itemId") == null) {
							continue;
						}
						elements.add(new DataElement(
							new Origin(origin.getString("itemId"), origin.getInteger("seq", 0),
								origin.getInteger("total")),
							element.getValue("value")));
					}
				}
				outputs.put(portId, new PortPayload(contentType, cardinality, elements));
			} catch (RuntimeException e) {
				// One unreadable port must not cost the caller the others.
				continue;
			}
		}
		return outputs;
	}
}
