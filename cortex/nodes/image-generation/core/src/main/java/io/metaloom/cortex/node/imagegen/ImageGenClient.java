package io.metaloom.cortex.node.imagegen;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

import javax.imageio.ImageIO;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * HTTP client for the image-generation sidecar. The Java {@link ImageGenNode} is a pure
 * HTTP client of this model server, mirroring the {@code SmolVLMClient} used by the
 * captioning node.
 *
 * <p>
 * Four endpoints are called, all returning {@code image/png} bytes:
 * </p>
 * <ul>
 * <li>{@code POST /generate} - text-to-image. Served by all three backends.</li>
 * <li>{@code POST /remix} - image-to-image. Served by all three backends.</li>
 * <li>{@code POST /edit} - several images plus an optional region mask.
 * <strong>Only {@code qwen-image-sidecar} serves it.</strong></li>
 * <li>{@code POST /mask} - text to binary mask.
 * <strong>Only {@code qwen-image-sidecar} serves it.</strong></li>
 * </ul>
 *
 * <p>
 * The connection is forced to HTTP/1.1 because the FastAPI sidecars reject the JDK
 * client's HTTP/2 upgrade attempt - {@code DepthmapClient}'s lesson, repeated in every
 * sidecar client in the tree.
 * </p>
 *
 * <p>
 * This is deliberately a <em>non-final class with non-final methods</em>: subclassing it
 * is how {@code ImageGenNodeIntegrationTest} and the docs-fixture recipe replace the
 * sidecar. It is also stateless, which is what lets one Dagger-provided instance be
 * shared by every {@code imagegen} node on the worker - see {@link ImageGenResult} for
 * why the model id is returned rather than remembered.
 * </p>
 */
public class ImageGenClient {

	private final String host;
	private final int port;
	private final String generateEndpoint;
	private final String remixEndpoint;
	private final String editEndpoint;
	private final String maskEndpoint;
	private final int timeoutMs;

	public ImageGenClient(String host, int port, String generateEndpoint, String remixEndpoint, String editEndpoint, String maskEndpoint,
		int timeoutMs) {
		this.host = host;
		this.port = port;
		this.generateEndpoint = generateEndpoint;
		this.remixEndpoint = remixEndpoint;
		this.editEndpoint = editEndpoint;
		this.maskEndpoint = maskEndpoint;
		this.timeoutMs = timeoutMs;
	}

	/**
	 * Text-to-image: generate a new image from the prompt.
	 */
	public ImageGenResult generate(String prompt, int width, int height, Integer seed, int steps, String negativePrompt, double trueCfgScale) {
		JsonObject json = new JsonObject()
			.put("prompt", prompt)
			.put("width", width)
			.put("height", height)
			.put("steps", steps);
		putSeed(json, seed);
		putGuidance(json, negativePrompt, trueCfgScale);
		return post(generateEndpoint, json);
	}

	/**
	 * Image-to-image / remix: transform the source image guided by the prompt.
	 */
	public ImageGenResult remix(BufferedImage source, String prompt, double strength, Integer seed, int steps, String negativePrompt,
		double trueCfgScale) {
		JsonObject json = new JsonObject()
			.put("image_b64", toBase64Png(source))
			.put("prompt", prompt)
			.put("strength", strength)
			.put("steps", steps);
		putSeed(json, seed);
		putGuidance(json, negativePrompt, trueCfgScale);
		return post(remixEndpoint, json);
	}

	/**
	 * Multi-image edit, optionally confined to a region.
	 *
	 * <p>
	 * {@code images} is the flat condition list the model reads, and its <em>order is
	 * significant</em>: element 0 is the image being edited and the rest are references.
	 * The mask is not one of them - it is passed separately as {@code mask_b64} and the
	 * sidecar appends it, because the sidecar also has to binarize it first.
	 * </p>
	 *
	 * @param images 1..10 images, the first being the one edited
	 * @param maskPng a mask PNG, or null
	 * @param maskPrompt names a region for the sidecar to derive the mask itself, or
	 *            null. Mutually exclusive with {@code maskPng} - the sidecar rejects both
	 *            together with a 400, and the node never sends both
	 * @param composite blend the result back through the mask so nothing outside it moves
	 */
	public ImageGenResult edit(List<BufferedImage> images, byte[] maskPng, String maskPrompt, String prompt, Integer seed, int steps,
		String negativePrompt, double trueCfgScale, int outputResolution, boolean composite) {
		JsonArray encoded = new JsonArray();
		for (BufferedImage image : images) {
			encoded.add(toBase64Png(image));
		}
		JsonObject json = new JsonObject()
			.put("prompt", prompt)
			.put("images_b64", encoded)
			.put("steps", steps)
			.put("output_resolution", outputResolution)
			.put("composite", composite);
		if (maskPng != null) {
			json.put("mask_b64", Base64.getEncoder().encodeToString(maskPng));
		} else if (maskPrompt != null && !maskPrompt.isBlank()) {
			json.put("mask_prompt", maskPrompt);
		}
		putSeed(json, seed);
		putGuidance(json, negativePrompt, trueCfgScale);
		return post(editEndpoint, json);
	}

	/**
	 * Produce a binary mask of the region named by {@code maskPrompt} - white where the
	 * region is. The returned PNG is 8-bit greyscale whose pixels are exactly 0 or 255;
	 * that polarity is a contract the {@code /edit} endpoint relies on.
	 */
	public ImageGenResult mask(BufferedImage source, String maskPrompt, Integer seed, int steps, int outputResolution) {
		JsonObject json = new JsonObject()
			.put("image_b64", toBase64Png(source))
			.put("prompt", maskPrompt)
			.put("steps", steps)
			.put("output_resolution", outputResolution);
		putSeed(json, seed);
		return post(maskEndpoint, json);
	}

	private static void putSeed(JsonObject json, Integer seed) {
		// Omitted rather than sent as null, so the sidecar picks a fresh seed per item.
		if (seed != null) {
			json.put("seed", seed);
		}
	}

	private static void putGuidance(JsonObject json, String negativePrompt, double trueCfgScale) {
		// Only sent when it can do anything: at true_cfg_scale 1.0 the negative branch is
		// not evaluated at all, so transmitting a negative prompt there would read in the
		// sidecar log as a setting that took effect when it did not. The older two
		// backends ignore both fields, which is why they are conditional rather than
		// always-on like `steps`.
		if (trueCfgScale > 1.0d) {
			json.put("true_cfg_scale", trueCfgScale);
			if (negativePrompt != null && !negativePrompt.isBlank()) {
				json.put("negative_prompt", negativePrompt);
			}
		}
	}

	protected ImageGenResult post(String endpoint, JsonObject json) {
		try {
			URI uri = new URI("http://" + host + ":" + port + endpoint);
			HttpRequest.Builder builder = HttpRequest.newBuilder()
				.uri(uri)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.encode()));
			if (timeoutMs > 0) {
				builder.timeout(Duration.ofMillis(timeoutMs));
			}
			// FastAPI requires HTTP/1.1.
			HttpClient client = HttpClient.newBuilder().version(Version.HTTP_1_1).build();
			HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new RuntimeException("Image sidecar returned HTTP " + status + " for " + endpoint + ": "
					+ new String(response.body()));
			}
			// Only qwen-image-sidecar sets this; absent on the other two backends.
			String modelId = response.headers().firstValue("X-Model-Id").orElse(null);
			return new ImageGenResult(response.body(), modelId);
		} catch (URISyntaxException | IOException e) {
			throw new RuntimeException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}

	private static String toBase64Png(BufferedImage image) {
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			// ImageUtils has a JPG writer only, so this goes through ImageIO directly.
			ImageIO.write(image, "png", bos);
			return Base64.getEncoder().encodeToString(bos.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
