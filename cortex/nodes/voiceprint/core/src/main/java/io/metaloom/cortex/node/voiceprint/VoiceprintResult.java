package io.metaloom.cortex.node.voiceprint;

import java.util.ArrayList;
import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The sidecar's answer to {@code POST /v1/voiceprints}, parsed once.
 *
 * <p>
 * Every time is an integer millisecond offset from the start of the audio the node sent - which is the asset's own timeline, because the node
 * demuxes a video's track without trimming it. Vectors are unit length; the sidecar normalises them and says so with {@code normalized}.
 * </p>
 *
 * @param model      the sidecar's full model id, e.g. {@code redimnet2-b6-vox2-lm}; part of every embedding row's identity
 * @param backend    the short backend name, e.g. {@code redimnet2}
 * @param dim        vector length
 * @param threshold  the cosine cut the clustering used
 * @param durationMs length of the audio
 * @param speechMs   how much of it the endpointer called speech
 * @param speakers   speakers ordered by speaking time, longest first
 * @param windows    the fixed-length speech windows, in time order, each attributed to a speaker
 * @param pooled     one vector over every window, or null when there was no speech
 */
public record VoiceprintResult(String model, String backend, int dim, double threshold, long durationMs, long speechMs,
	List<Speaker> speakers, List<Window> windows, float[] pooled) {

	public record Segment(long startMs, long endMs) {
	}

	public record Speaker(int index, long totalMs, double cohesion, List<Integer> windows, List<Segment> segments, float[] vector) {
	}

	public record Window(int index, long startMs, long endMs, int speaker, float[] vector) {
	}

	public boolean hasSpeech() {
		return !speakers.isEmpty();
	}

	public static VoiceprintResult fromJson(JsonObject json) {
		List<Speaker> speakers = new ArrayList<>();
		for (Object o : json.getJsonArray("speakers", new JsonArray())) {
			JsonObject s = (JsonObject) o;
			List<Integer> members = new ArrayList<>();
			for (Object m : s.getJsonArray("windows", new JsonArray())) {
				members.add(((Number) m).intValue());
			}
			List<Segment> segments = new ArrayList<>();
			for (Object g : s.getJsonArray("segments", new JsonArray())) {
				JsonObject seg = (JsonObject) g;
				segments.add(new Segment(seg.getLong("start_ms"), seg.getLong("end_ms")));
			}
			speakers.add(new Speaker(s.getInteger("index"), s.getLong("total_ms", 0L), s.getDouble("cohesion", 1.0), members, segments,
				vector(s.getJsonArray("vector"))));
		}
		List<Window> windows = new ArrayList<>();
		for (Object o : json.getJsonArray("windows", new JsonArray())) {
			JsonObject w = (JsonObject) o;
			windows.add(new Window(w.getInteger("index"), w.getLong("start_ms"), w.getLong("end_ms"), w.getInteger("speaker"),
				vector(w.getJsonArray("vector"))));
		}
		JsonObject pooled = json.getJsonObject("pooled");
		return new VoiceprintResult(json.getString("model"), json.getString("backend"), json.getInteger("dim", 0),
			json.getDouble("threshold", 0.0), json.getLong("duration_ms", 0L), json.getLong("speech_ms", 0L), speakers, windows,
			pooled == null ? null : vector(pooled.getJsonArray("vector")));
	}

	static float[] vector(JsonArray array) {
		if (array == null) {
			return null;
		}
		float[] out = new float[array.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = ((Number) array.getValue(i)).floatValue();
		}
		return out;
	}

	static JsonArray toJson(float[] vector) {
		JsonArray out = new JsonArray();
		if (vector != null) {
			for (float v : vector) {
				out.add(v);
			}
		}
		return out;
	}

	/**
	 * The timeline without the vectors: what the node persists as a JSON component and emits on its {@code result} port. The vectors live in
	 * {@code embedding} rows and would only bloat a document meant to be read.
	 */
	public JsonObject toSummaryJson() {
		JsonObject out = new JsonObject()
			.put("model", model)
			.put("backend", backend)
			.put("dim", dim)
			.put("threshold", threshold)
			.put("durationMs", durationMs)
			.put("speechMs", speechMs)
			.put("speakerCount", speakers.size());
		JsonArray sp = new JsonArray();
		for (Speaker s : speakers) {
			JsonArray segs = new JsonArray();
			for (Segment g : s.segments()) {
				segs.add(new JsonObject().put("startMs", g.startMs()).put("endMs", g.endMs()));
			}
			sp.add(new JsonObject().put("index", s.index()).put("totalMs", s.totalMs()).put("cohesion", s.cohesion())
				.put("windowCount", s.windows().size()).put("segments", segs));
		}
		out.put("speakers", sp);
		JsonArray win = new JsonArray();
		for (Window w : windows) {
			win.add(new JsonObject().put("index", w.index()).put("startMs", w.startMs()).put("endMs", w.endMs()).put("speaker", w.speaker()));
		}
		out.put("windows", win);
		return out;
	}

	/**
	 * One speaker as the element the {@code speakers} port carries: its index, timing and voiceprint.
	 */
	public static JsonObject speakerJson(Speaker s) {
		JsonArray segs = new JsonArray();
		for (Segment g : s.segments()) {
			segs.add(new JsonObject().put("startMs", g.startMs()).put("endMs", g.endMs()));
		}
		return new JsonObject().put("index", s.index()).put("totalMs", s.totalMs()).put("cohesion", s.cohesion())
			.put("segments", segs).put("vector", toJson(s.vector()));
	}

	/**
	 * The speaker turns as a {@code struct/segments} document: every segment with its speaker, in time order.
	 */
	public JsonObject toTimelineJson() {
		List<JsonObject> turns = new ArrayList<>();
		for (Speaker s : speakers) {
			for (Segment g : s.segments()) {
				turns.add(new JsonObject().put("start", g.startMs()).put("end", g.endMs()).put("speaker", s.index())
					.put("label", "Speaker " + (s.index() + 1)));
			}
		}
		turns.sort((a, b) -> Long.compare(a.getLong("start"), b.getLong("start")));
		return new JsonObject().put("durationMs", durationMs).put("segments", new JsonArray(turns));
	}
}
