package io.metaloom.loom.mcp.imagegen;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.options.ImageGenToolOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Loom's own HTTP client for the image-generation sidecar, used by the MCP {@code generate_image}
 * tool.
 *
 * <p>
 * Loom had no sidecar client before this one — every other model server in the tree is called from a
 * Cortex node. It is a second, smaller client rather than a reuse of
 * {@code io.metaloom.cortex.node.imagegen.ImageGenClient} because that class lives in a Cortex node
 * module, and Loom depending on a Cortex node would invert the dependency direction the whole
 * architecture rests on. The two speak the same wire format and are expected to stay in step; the
 * contract they share is written down in {@code spec/sidecars/QWEN_IMAGE_SIDECAR.md}.
 * </p>
 *
 * <p>
 * Non-final with non-final methods on purpose: subclassing it is how the tool's tests replace the
 * sidecar, matching the Cortex-side convention.
 * </p>
 */
@Singleton
public class ImageEditClient {

	private final ImageGenToolOptions options;

	@Inject
	public ImageEditClient(LoomOptions loomOptions) {
		this.options = loomOptions.getImageGenTool();
	}

	/**
	 * Generate or edit an image.
	 *
	 * <p>
	 * With no images this is text-to-image and {@code /generate} is called. With one or more it is
	 * an edit and {@code /edit} is called — the first image is the one being changed and the rest
	 * are references, an ordering the sidecar relies on.
	 * </p>
	 *
	 * @param prompt what to draw, or what to change
	 * @param images the input images as raw bytes, in order; may be empty
	 * @param maskPrompt names a region to confine the edit to, or null. The sidecar derives the
	 *            mask itself and applies it in the same request
	 * @param width text-to-image width, ignored for an edit (which follows the source)
	 * @param height text-to-image height, ignored for an edit
	 * @param seed fixed seed, or null to let the sidecar pick one
	 * @return the produced PNG bytes
	 */
	public byte[] generate(String prompt, List<byte[]> images, String maskPrompt, Integer width, Integer height, Integer seed) {
		JsonObject json = new JsonObject()
			.put("prompt", prompt)
			.put("steps", options.getSteps());
		if (seed != null) {
			json.put("seed", seed);
		}

		if (images == null || images.isEmpty()) {
			json.put("width", width != null ? width : 1024);
			json.put("height", height != null ? height : 1024);
			return post("/generate", json);
		}

		JsonArray encoded = new JsonArray();
		for (byte[] image : images) {
			encoded.add(Base64.getEncoder().encodeToString(image));
		}
		json.put("images_b64", encoded);
		if (maskPrompt != null && !maskPrompt.isBlank()) {
			json.put("mask_prompt", maskPrompt);
		}
		return post("/edit", json);
	}

	protected byte[] post(String endpoint, JsonObject json) {
		try {
			URI uri = new URI("http://" + options.getHost() + ":" + options.getPort() + endpoint);
			HttpRequest request = HttpRequest.newBuilder()
				.uri(uri)
				.header("Content-Type", "application/json")
				.timeout(Duration.ofMillis(options.getTimeoutMs()))
				.POST(HttpRequest.BodyPublishers.ofString(json.encode()))
				.build();
			// FastAPI rejects the JDK client's HTTP/2 upgrade attempt - the lesson every sidecar
			// client in this tree carries.
			HttpClient client = HttpClient.newBuilder().version(Version.HTTP_1_1).build();
			HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new RuntimeException("The image sidecar returned HTTP " + status + " for " + endpoint + ": "
					+ new String(response.body()));
			}
			return response.body();
		} catch (URISyntaxException | IOException e) {
			throw new RuntimeException("Could not reach the image sidecar at " + options.getHost() + ":" + options.getPort()
				+ " - is it running?", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}
}
