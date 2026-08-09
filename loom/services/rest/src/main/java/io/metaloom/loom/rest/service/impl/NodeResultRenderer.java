package io.metaloom.loom.rest.service.impl;

import java.util.List;
import java.util.Map;

import io.metaloom.loom.pipeline.model.DataElement;
import io.metaloom.loom.pipeline.model.MediaRef;
import io.metaloom.loom.pipeline.model.NodeTaskResult;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Renders node outputs for the two audiences an ad-hoc run has.
 *
 * <p>
 * A REST client wants the payloads as they are - typed, with cardinality and origins intact - and
 * gets {@link #renderOutputs(Map)}. A language model wants a few lines it can reason about and must
 * not be handed an unbounded blob, because the agent loop feeds a tool result straight into the next
 * turn's context; that is {@link #renderText}.
 * </p>
 *
 * <p>
 * Truncation is always announced in the text. A silently shortened transcript reads to the model as
 * a complete one, and it will answer confidently about the part it never saw.
 * </p>
 */
public final class NodeResultRenderer {

	/** Longest single value rendered inline before it is elided. */
	private static final int MAX_VALUE_CHARS = 600;

	/** Elements listed per port before the rest are summarised as a count. */
	private static final int MAX_ELEMENTS = 10;

	private NodeResultRenderer() {
	}

	/**
	 * One item's results as bounded, model-readable text.
	 *
	 * @param media    the item the nodes ran against; its filename anchors the text
	 * @param results  results by node id, in graph order as the engine recorded them
	 * @param maxChars hard cap on the returned string
	 */
	public static String renderText(MediaRef media, Map<String, NodeTaskResult> results, int maxChars) {
		StringBuilder text = new StringBuilder();
		text.append(filenameOf(media)).append('\n');

		if (results == null || results.isEmpty()) {
			text.append("  (no node produced a result)");
			return cap(text.toString(), maxChars);
		}

		for (Map.Entry<String, NodeTaskResult> entry : results.entrySet()) {
			NodeTaskResult result = entry.getValue();
			if (result == null) {
				continue;
			}
			// The synthesised source result is an artefact of how the engine feeds a graph, not
			// something the caller asked for; listing it would make every probe look like two nodes.
			if (AdHocGraphBuilder.SOURCE_NODE_ID.equals(entry.getKey())) {
				continue;
			}
			text.append("  ").append(entry.getKey()).append(": ").append(result.getState());
			if (result.getDurationMs() > 0) {
				text.append(" (").append(result.getDurationMs()).append("ms)");
			}
			if (result.getMessage() != null && !result.getMessage().isBlank()) {
				text.append(" - ").append(result.getMessage());
			}
			text.append('\n');
			appendOutputs(text, result.getOutputs());
		}
		return cap(text.toString(), maxChars);
	}

	private static void appendOutputs(StringBuilder text, Map<String, PortPayload> outputs) {
		if (outputs == null || outputs.isEmpty()) {
			return;
		}
		for (Map.Entry<String, PortPayload> port : outputs.entrySet()) {
			PortPayload payload = port.getValue();
			if (payload == null || payload.isEmpty()) {
				continue;
			}
			List<DataElement> elements = payload.getElements();
			text.append("    ").append(port.getKey())
				.append(" [").append(payload.getContentType()).append("]: ");
			int shown = Math.min(elements.size(), MAX_ELEMENTS);
			for (int i = 0; i < shown; i++) {
				if (i > 0) {
					text.append(", ");
				}
				text.append(abbreviate(String.valueOf(elements.get(i).getValue())));
			}
			if (elements.size() > shown) {
				text.append(", ... ").append(elements.size() - shown).append(" more");
			}
			text.append('\n');
		}
	}

	/**
	 * The port payloads as JSON, preserving type, cardinality and element origins.
	 *
	 * <p>
	 * Deliberately the same shape {@code pipeline_node_task.outputs} is persisted in, so a caller
	 * reading a live probe and a caller reading a finished run see one format.
	 * </p>
	 */
	public static JsonObject renderOutputs(Map<String, PortPayload> outputs) {
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
				JsonObject rendered = new JsonObject().put("value", element.getValue());
				if (element.getOrigin() != null) {
					rendered.put("seq", element.getOrigin().getSeq());
				}
				elements.add(rendered);
			}
			json.put(entry.getKey(), new JsonObject()
				.put("contentType", payload.getContentType())
				.put("cardinality", payload.getCardinality())
				.put("elements", elements));
		}
		return json;
	}

	/**
	 * Cap a string, saying so.
	 *
	 * @param maxChars a non-positive value means no cap
	 */
	public static String cap(String text, int maxChars) {
		if (text == null || maxChars <= 0 || text.length() <= maxChars) {
			return text;
		}
		String notice = "\n... truncated at " + maxChars + " characters.";
		int keep = Math.max(0, maxChars - notice.length());
		return text.substring(0, keep) + notice;
	}

	private static String abbreviate(String value) {
		if (value.length() <= MAX_VALUE_CHARS) {
			return value;
		}
		return value.substring(0, MAX_VALUE_CHARS) + "...(" + value.length() + " chars)";
	}

	private static String filenameOf(MediaRef media) {
		if (media == null || media.getPath() == null) {
			return "(unknown media)";
		}
		String path = media.getPath();
		int slash = path.lastIndexOf('/');
		return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
	}

}
