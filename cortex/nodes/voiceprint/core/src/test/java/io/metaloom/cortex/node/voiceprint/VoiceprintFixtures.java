package io.metaloom.cortex.node.voiceprint;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A sidecar answer in the exact wire shape {@code sidecars/voiceprint/server.py} produces (snake_case, integer ms), for two speakers over four
 * windows, plus a tiny real WAV for the demuxer.
 */
final class VoiceprintFixtures {

	static final String MODEL = "redimnet2-b6-vox2-lm";

	private VoiceprintFixtures() {
	}

	static JsonArray vec(double... v) {
		JsonArray a = new JsonArray();
		for (double d : v) {
			a.add(d);
		}
		return a;
	}

	static JsonObject sidecarJson() {
		return new JsonObject()
			.put("model", MODEL).put("backend", "redimnet2").put("dim", 3).put("normalized", true).put("threshold", 0.4)
			.put("duration_ms", 12_000).put("speech_ms", 9_000).put("window_s", 3.0).put("hop_s", 1.5).put("compute_ms", 42)
			.put("pooled", new JsonObject().put("vector", vec(0.6, 0.8, 0.0)))
			.put("speakers", new JsonArray()
				.add(new JsonObject().put("index", 0).put("total_ms", 6_000).put("cohesion", 0.93).put("windows", new JsonArray().add(0).add(1))
					.put("segments", new JsonArray().add(new JsonObject().put("start_ms", 1_000).put("end_ms", 5_500)))
					.put("vector", vec(1.0, 0.0, 0.0)))
				.add(new JsonObject().put("index", 1).put("total_ms", 3_000).put("cohesion", 0.88).put("windows", new JsonArray().add(2).add(3))
					.put("segments", new JsonArray().add(new JsonObject().put("start_ms", 7_000).put("end_ms", 10_000)))
					.put("vector", vec(0.0, 1.0, 0.0))))
			.put("windows", new JsonArray()
				.add(window(0, 1_000, 4_000, 0, vec(0.99, 0.14, 0.0)))
				.add(window(1, 2_500, 5_500, 0, vec(0.95, 0.31, 0.0)))
				.add(window(2, 7_000, 10_000, 1, vec(0.1, 0.99, 0.0)))
				.add(window(3, 7_000, 10_000, 1, vec(0.0, 1.0, 0.0))));
	}

	private static JsonObject window(int i, long s, long e, int speaker, JsonArray v) {
		return new JsonObject().put("index", i).put("start_ms", s).put("end_ms", e).put("speaker", speaker).put("vector", v);
	}

	static VoiceprintResult result() {
		return VoiceprintResult.fromJson(sidecarJson());
	}

	static VoiceprintResult silent() {
		return VoiceprintResult.fromJson(new JsonObject().put("model", MODEL).put("backend", "redimnet2").put("dim", 3).put("threshold", 0.4)
			.put("duration_ms", 5_000).put("speech_ms", 0).put("speakers", new JsonArray()).put("windows", new JsonArray()));
	}

	/** One second of a 440 Hz tone as a 16 kHz mono PCM16 WAV. */
	static File toneWav(File dir, String name) throws IOException {
		int rate = 16_000;
		int n = rate;
		ByteBuffer buf = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN);
		buf.put("RIFF".getBytes()).putInt(36 + n * 2).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1)
			.putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(n * 2);
		for (int i = 0; i < n; i++) {
			buf.putShort((short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000));
		}
		Path p = dir.toPath().resolve(name);
		Files.write(p, buf.array());
		return p.toFile();
	}

	static boolean ffmpegAvailable() {
		try {
			return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}
}
