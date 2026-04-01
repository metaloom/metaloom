package io.metaloom.cortex.media.whisper;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class WhisperResult {

	private List<TranscriptionSegment> scenes = new ArrayList<>();

	public void addTranscriptionSegment(TranscriptionSegment scene) {
		scenes.add(scene);
	}

	public List<TranscriptionSegment> segments() {
		return scenes;
	}

	/**
	 * Serialize to JSON string using Vert.x JsonObject.
	 */
	public String toJson() {
		JsonArray arr = new JsonArray();
		for (TranscriptionSegment seg : scenes) {
			arr.add(new JsonObject()
				.put("text", seg.getText())
				.put("from", seg.getFrom())
				.put("to", seg.getTo()));
		}
		return new JsonObject().put("segments", arr).encode();
	}

	/**
	 * Deserialize from the JSON string produced by {@link #toJson()}.
	 */
	public static WhisperResult fromJson(String json) {
		WhisperResult result = new WhisperResult();
		if (json == null || json.isBlank()) {
			return result;
		}
		JsonObject obj = new JsonObject(json);
		JsonArray arr = obj.getJsonArray("segments");
		if (arr != null) {
			for (int i = 0; i < arr.size(); i++) {
				JsonObject seg = arr.getJsonObject(i);
				result.addTranscriptionSegment(new TranscriptionSegment(
					seg.getString("text"),
					seg.getLong("from"),
					seg.getLong("to")));
			}
		}
		return result;
	}

}
