package io.metaloom.loom.pipeline.engine;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import io.metaloom.loom.pipeline.model.NodePreview;
import io.vertx.core.json.JsonObject;

/**
 * Converts node previews to and from plain JSON for the {@code pipeline_node_task.previews} column.
 *
 * <p>
 * The sibling of {@link PortPayloads}, and deliberately the same shape of thing: Vert.x's
 * {@code JsonObject} accepts only JSON-native values, so the encoding lives in one place rather than
 * being improvised per call site.
 * </p>
 *
 * <p>
 * Bytes travel as base64. That is what JSON can carry, and a preview is capped at 96 KiB at the
 * source precisely so that the ~33% base64 overhead lands somewhere harmless.
 * </p>
 *
 * <p>
 * Decoding is <strong>lenient</strong> for the same reason as {@code PortPayloads}: a malformed
 * preview is a missing thumbnail, and a missing thumbnail must never be able to fail a read of the
 * run state it is attached to.
 * </p>
 */
public final class NodePreviews {

	private NodePreviews() {
	}

	/** Encode previews for storage, keyed by output port id. */
	public static JsonObject encode(Map<String, NodePreview> previews) {
		JsonObject json = new JsonObject();
		if (previews == null) {
			return json;
		}
		for (Map.Entry<String, NodePreview> entry : previews.entrySet()) {
			NodePreview preview = entry.getValue();
			if (preview == null) {
				continue;
			}
			JsonObject encoded = new JsonObject();
			if (preview.hasData()) {
				encoded
					.put("mimeType", preview.getMimeType())
					.put("width", preview.getWidth())
					.put("height", preview.getHeight())
					.put("data", Base64.getEncoder().encodeToString(preview.getData()));
			} else if (preview.getSkippedReason() != null) {
				// A skip is stored, not dropped. "Too large to preview" and "this port emitted
				// nothing" look identical to a reader otherwise, and they mean opposite things.
				encoded.put("skippedReason", preview.getSkippedReason());
			}
			// Independent of the bytes: a port can have both an image and the node's own
			// description of it, and neither displaces the other.
			if (preview.hasMarkdown()) {
				encoded.put("markdown", preview.getMarkdown());
			}
			if (encoded.isEmpty()) {
				continue;
			}
			json.put(entry.getKey(), encoded);
		}
		return json;
	}

	/** Decode stored previews. Anything unreadable is skipped rather than thrown. */
	public static Map<String, NodePreview> decode(JsonObject json) {
		Map<String, NodePreview> previews = new LinkedHashMap<>();
		if (json == null) {
			return previews;
		}
		for (String portId : json.fieldNames()) {
			try {
				JsonObject encoded = json.getJsonObject(portId);
				if (encoded == null) {
					continue;
				}
				String data = encoded.getString("data");
				String markdown = encoded.getString("markdown");
				NodePreview preview = data == null
					? NodePreview.skipped(encoded.getString("skippedReason"))
					: NodePreview.image(
						encoded.getString("mimeType"),
						encoded.getInteger("width", 0),
						encoded.getInteger("height", 0),
						Base64.getDecoder().decode(data));
				previews.put(portId, markdown == null ? preview : preview.withMarkdown(markdown));
			} catch (Exception e) {
				// A hand-edited row, or one written by a future shape. Drop this one port.
			}
		}
		return previews;
	}
}
