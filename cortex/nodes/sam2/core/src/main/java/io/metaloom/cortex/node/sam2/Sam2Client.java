package io.metaloom.cortex.node.sam2;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Minimal Java-only client for the FastAPI SAM 2 sidecar ({@code sidecars/sam2}).
 *
 * <p>
 * Mirrors {@code DepthmapClient}: it forces HTTP/1.1 because FastAPI rejects the JDK client's default
 * HTTP/2 upgrade attempt.
 * </p>
 *
 * <p>
 * 🔴 Every box crossing this boundary is <strong>XYXY, in the pixel space of the image being
 * posted</strong> — not XYWH, and not the source resolution. The node converts and rescales before
 * calling; the sidecar never learns the source size and must not have to guess it.
 * </p>
 *
 * <p>
 * This class is the seam the tests replace - the Dagger module provides one instance, and both the
 * unit tests and the integration test hand the node a stub instead of a real model. Neither the class
 * nor its methods are final, so subclassing works.
 * </p>
 */
public class Sam2Client {

	private final String host;
	private final int port;
	private final int timeoutMs;

	public Sam2Client(String host, int port, int timeoutMs) {
		this.host = host;
		this.port = port;
		this.timeoutMs = timeoutMs;
	}

	/**
	 * Segment one still image and return the sidecar's result:
	 * {@code {model, mode, width, height, masks[{index, png_b64, area, score?, bbox{x,y,w,h}, label?, promptIndex?}], truncated{masks}}}.
	 *
	 * <p>
	 * Each {@code png_b64} is an 8-bit grayscale PNG whose pixels are 0 or 255. The {@code width} /
	 * {@code height} describe the returned <em>masks</em>, which are the source image downscaled to
	 * {@code maxDim} — not the source image itself.
	 * </p>
	 *
	 * @param imageB64 the base64 PNG of the (already downscaled) image
	 * @param mode     {@link Sam2Mode#AUTOMATIC} or {@link Sam2Mode#PROMPTED}
	 * @param boxes    the prompts for PROMPTED, in the posted image's pixel space; ignored otherwise
	 * @param options  supplies model, dimensions, thresholds and caps
	 * @return the segmentation result
	 */
	public JsonObject segment(String imageB64, Sam2Mode mode, List<Sam2Box> boxes, Sam2NodeOptions options) {
		JsonObject json = new JsonObject()
			.put("image_b64", imageB64)
			.put("mode", (mode == null ? Sam2Mode.AUTOMATIC : mode).name())
			.put("max_dim", options.getMaxDim())
			.put("points_per_side", options.getPointsPerSide())
			.put("pred_iou_thresh", options.getPredIouThresh())
			.put("stability_score_thresh", options.getStabilityScoreThresh())
			.put("min_mask_area", options.getMinMaskArea())
			.put("max_masks", options.getMaxMasks())
			.put("multimask", options.isMultimask());
		putModel(json, options);
		if (boxes != null && !boxes.isEmpty()) {
			json.put("boxes", encodeBoxes(boxes));
		}
		return post("/v1/segment", json);
	}

	/**
	 * Track prompted objects through a sampled frame sequence and return the sidecar's result:
	 * {@code {model, mode, width, height, frameCount, objects[], frames[{index, frameNumber, masks[{objId, png_b64, area, bbox}]}], truncated{}}}.
	 *
	 * @param framesB64    the sampled frames as base64 JPEGs, in order
	 * @param frameNumbers the <em>source</em> frame number of each entry, echoed back verbatim
	 * @param prompts      one prompt per object, each naming an index into {@code framesB64}
	 * @param options      supplies model, dimensions and caps
	 * @return the tracking result
	 */
	public JsonObject track(List<String> framesB64, List<Integer> frameNumbers, List<Sam2TrackPrompt> prompts,
		Sam2NodeOptions options) {
		JsonArray promptArray = new JsonArray();
		for (Sam2TrackPrompt prompt : prompts) {
			promptArray.add(new JsonObject()
				.put("obj_id", prompt.objId())
				.put("frame_index", prompt.frameIndex())
				.put("box", encodeBox(prompt.box())));
		}

		JsonObject json = new JsonObject()
			.put("frames_b64", new JsonArray(framesB64))
			.put("frame_numbers", new JsonArray(frameNumbers))
			.put("max_dim", options.getMaxDim())
			.put("max_masks", options.getMaxMasks())
			.put("prompts", promptArray);
		putModel(json, options);
		return post("/v1/track", json);
	}

	private static void putModel(JsonObject json, Sam2NodeOptions options) {
		String model = options.getModel();
		if (model != null && !model.isBlank()) {
			json.put("model", model);
		}
	}

	private static JsonArray encodeBoxes(List<Sam2Box> boxes) {
		JsonArray array = new JsonArray();
		for (Sam2Box box : boxes) {
			array.add(encodeBox(box));
		}
		return array;
	}

	private static JsonObject encodeBox(Sam2Box box) {
		JsonObject json = new JsonObject()
			.put("x1", box.x1())
			.put("y1", box.y1())
			.put("x2", box.x2())
			.put("y2", box.y2());
		if (box.label() != null) {
			json.put("label", box.label());
		}
		if (box.objId() != null) {
			json.put("obj_id", box.objId());
		}
		return json;
	}

	private JsonObject post(String path, JsonObject json) {
		URI uri = URI.create("http://" + host + ":" + port + path);
		HttpRequest request = HttpRequest.newBuilder()
			.uri(uri)
			.timeout(Duration.ofMillis(timeoutMs))
			.header("Content-Type", "application/json")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json.encode(), StandardCharsets.UTF_8))
			.build();

		// Force HTTP/1.1 - FastAPI rejects the default HTTP/2 upgrade attempt.
		HttpClient client = HttpClient.newBuilder()
			.version(Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(30))
			.build();

		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			if (response.statusCode() / 100 != 2) {
				throw new RuntimeException("SAM 2 request failed (HTTP " + response.statusCode() + "): " + response.body());
			}
			return new JsonObject(response.body());
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new RuntimeException("SAM 2 request to " + uri + " failed", e);
		}
	}
}
