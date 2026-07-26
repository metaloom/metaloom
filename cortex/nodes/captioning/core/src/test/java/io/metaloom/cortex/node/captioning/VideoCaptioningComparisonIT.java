package io.metaloom.cortex.node.captioning;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.video4j.Video4j;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Env-gated comparison harness. It drives every {@link VideoCaptioningStrategy} of the merged {@link CaptioningNode} (whole-video, scene-first,
 * native-video) against every reachable OpenAI-compatible endpoint (vLLM, llama.cpp) on a set of real video clips, capturing the produced caption and both
 * the model latency and the end-to-end wall time. Results are written to a JSON file for the report and printed as a table.
 *
 * <p>Nothing here needs a Loom backend: the node is constructed with a null client, so {@link CaptioningNode#captionVideo(LoomMedia)} runs the real
 * sampling + model call and skips persistence. The test self-skips (JUnit assumption) when no endpoint is reachable, so it is safe in ordinary CI.
 *
 * <p>Configuration via environment variables (all optional):
 * <ul>
 * <li>{@code LLAMACPP_URL} (default {@code http://127.0.0.1:8081}), {@code LLAMACPP_MODEL} (default {@code qwen})</li>
 * <li>{@code VLLM_URL} (default {@code http://127.0.0.1:8000}), {@code VLLM_MODEL} (default {@code qwen25vl-awq})</li>
 * <li>{@code TEST_VIDEOS} - comma-separated absolute paths (defaults to two clips under the video4j workspace)</li>
 * <li>{@code RESULTS_FILE} - where to write the JSON results (default {@code target/video-caption-results.json})</li>
 * <li>{@code FRAME_COUNT} (default 8), {@code FRAME_SIZE} (default 512), {@code MAX_TOKENS} (default 256)</li>
 * </ul>
 */
public class VideoCaptioningComparisonIT {

	private record Endpoint(String name, String url, String model, boolean nativeVideo) {
	}

	private static String env(String key, String def) {
		String v = System.getenv(key);
		return (v == null || v.isBlank()) ? def : v;
	}

	private static int envInt(String key, int def) {
		String v = System.getenv(key);
		return (v == null || v.isBlank()) ? def : Integer.parseInt(v.trim());
	}

	@Test
	public void compareVariantsAcrossModels() throws Exception {
		List<Endpoint> endpoints = List.of(
			new Endpoint("llamacpp-qwen25vl-7b-q4km", env("LLAMACPP_URL", "http://127.0.0.1:8081"), env("LLAMACPP_MODEL", "qwen"), false),
			new Endpoint("vllm-qwen25vl-7b-awq", env("VLLM_URL", "http://127.0.0.1:8000"), env("VLLM_MODEL", "qwen25vl-awq"), true));

		List<Endpoint> reachable = endpoints.stream().filter(e -> isReachable(e.url())).toList();
		System.out.println("[harness] reachable endpoints: " + reachable.stream().map(Endpoint::name).toList());
		Assumptions.assumeFalse(reachable.isEmpty(), "No captioning endpoint reachable - start vLLM and/or llama.cpp first");

		List<String> videos = new ArrayList<>();
		String tv = System.getenv("TEST_VIDEOS");
		if (tv != null && !tv.isBlank()) {
			for (String p : tv.split(",")) {
				videos.add(p.trim());
			}
		} else {
			videos.add("/home/defaultuser/workspaces/metaloom/video4j/media/pexels-8090198-sd_640_338_25fps.mp4");
			videos.add("/home/defaultuser/workspaces/metaloom/media/demo.mp4");
		}
		videos.removeIf(p -> !new java.io.File(p).exists());
		Assumptions.assumeFalse(videos.isEmpty(), "No test videos found");

		int frameCount = envInt("FRAME_COUNT", 8);
		int frameSize = envInt("FRAME_SIZE", 512);
		int maxTokens = envInt("MAX_TOKENS", 256);

		Video4j.init();
		CortexOptions cortex = new CortexOptions();
		VideoCaptioningStrategy[] variants = VideoCaptioningStrategy.values();

		JsonArray results = new JsonArray();
		System.out.printf("%n%-26s %-8s %-30s %8s %8s %6s %6s  %s%n",
			"endpoint", "variant", "video", "wall_ms", "model_ms", "frames", "scenes", "caption(preview)");
		System.out.println("-".repeat(160));

		for (String videoPath : videos) {
			String videoName = new java.io.File(videoPath).getName();
			for (Endpoint ep : reachable) {
				for (VideoCaptioningStrategy variant : variants) {
					// Native video only meaningful where the server decodes video; still probe llama.cpp once to record the (expected) failure.
					CaptioningNodeOptions options = new CaptioningNodeOptions()
						.setVideoStrategy(variant)
						.setVideoEndpointUrl(ep.url()).setVideoModel(ep.model())
						.setFrameCount(frameCount).setTargetFrameSize(frameSize).setMaxTokens(maxTokens);
					VideoVLMClient vlm = new VideoVLMClient(ep.url(), ep.model(), "", maxTokens, 0.2d);
					LoomMedia media = HarnessMedia.video(videoPath);
					CaptioningNode node = new CaptioningNode(null, cortex, options, new SmolVLMClient("localhost", 0), vlm);
					node.initialize();

					JsonObject row = new JsonObject()
						.put("endpoint", ep.name()).put("model", ep.model())
						.put("variant", "captioning-" + variant.name().toLowerCase()).put("video", videoName);
					long t0 = System.currentTimeMillis();
					try {
						VideoCaptionOutput out = node.captionVideo(media);
						long wall = System.currentTimeMillis() - t0;
						row.put("ok", true).put("wall_ms", wall).put("model_ms", out.modelLatencyMs())
							.put("frames", out.frameCount()).put("scenes", out.scenes() == null ? 0 : out.scenes().size())
							.put("caption", out.caption());
						System.out.printf("%-26s %-8s %-30s %8d %8d %6d %6d  %s%n",
							ep.name(), variant.name().toLowerCase(), trunc(videoName, 30), wall, out.modelLatencyMs(),
							out.frameCount(), out.scenes() == null ? 0 : out.scenes().size(), trunc(oneLine(out.caption()), 80));
					} catch (Exception e) {
						long wall = System.currentTimeMillis() - t0;
						row.put("ok", false).put("wall_ms", wall).put("error", String.valueOf(e.getMessage()));
						System.out.printf("%-26s %-8s %-30s %8d %8s %6s %6s  ERROR: %s%n",
							ep.name(), variant.name().toLowerCase(), trunc(videoName, 30), wall, "-", "-", "-",
							trunc(oneLine(String.valueOf(e.getMessage())), 90));
					}
					results.add(row);
				}
			}
		}

		Path out = Path.of(env("RESULTS_FILE", "target/video-caption-results.json"));
		Files.createDirectories(out.toAbsolutePath().getParent());
		Files.writeString(out, results.encodePrettily());
		System.out.println("\n[harness] wrote results to " + out.toAbsolutePath());
	}

	private static boolean isReachable(String baseUrl) {
		try {
			HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
			HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl.replaceAll("/+$", "") + "/v1/models"))
				.timeout(Duration.ofSeconds(3)).GET().build();
			HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
			return r.statusCode() == 200;
		} catch (IOException | InterruptedException e) {
			return false;
		}
	}

	private static String oneLine(String s) {
		return s == null ? "" : s.replaceAll("\\s+", " ").trim();
	}

	private static String trunc(String s, int n) {
		if (s == null) {
			return "";
		}
		return s.length() <= n ? s : s.substring(0, n - 1) + "…";
	}
}
