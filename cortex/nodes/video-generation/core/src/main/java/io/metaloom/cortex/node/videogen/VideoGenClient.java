package io.metaloom.cortex.node.videogen;

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

import javax.imageio.ImageIO;

import io.vertx.core.json.JsonObject;

/**
 * HTTP client for the LTX-2 video sidecar (see {@code sidecars/ltx2-sidecar}).
 * The Java {@link VideoGenNode} is a pure HTTP client of this model server, mirroring
 * the {@code ImageGenClient} used by the image-generation node.
 *
 * <p>
 * Two model-agnostic endpoints are called, both returning {@code video/mp4} bytes (an
 * H.264 video track plus LTX-2's synchronised audio): {@code POST /generate}
 * (text-to-video) and {@code POST /animate} (image-to-video). The connection is forced
 * to HTTP/1.1 because the FastAPI sidecar rejects HTTP/2.
 * </p>
 */
public class VideoGenClient {

	private final String host;
	private final int port;
	private final String generateEndpoint;
	private final String animateEndpoint;
	private final int timeoutMs;

	public VideoGenClient(String host, int port, String generateEndpoint, String animateEndpoint, int timeoutMs) {
		this.host = host;
		this.port = port;
		this.generateEndpoint = generateEndpoint;
		this.animateEndpoint = animateEndpoint;
		this.timeoutMs = timeoutMs;
	}

	/**
	 * Text-to-video: synthesise a new clip from the prompt. Returns the raw MP4 bytes.
	 */
	public byte[] generate(String prompt, String negativePrompt, int width, int height, int numFrames, int fps, int steps, double guidance,
		Integer seed) {
		return post(generateEndpoint, baseBody(prompt, negativePrompt, width, height, numFrames, fps, steps, guidance, seed));
	}

	/**
	 * Image-to-video / animate: use the source image as the opening frame and evolve it guided by the prompt. Returns the raw MP4 bytes.
	 */
	public byte[] animate(BufferedImage source, String prompt, String negativePrompt, int width, int height, int numFrames, int fps, int steps,
		double guidance, Integer seed) {
		JsonObject json = baseBody(prompt, negativePrompt, width, height, numFrames, fps, steps, guidance, seed)
			.put("image_b64", toBase64Png(source));
		return post(animateEndpoint, json);
	}

	private static JsonObject baseBody(String prompt, String negativePrompt, int width, int height, int numFrames, int fps, int steps,
		double guidance, Integer seed) {
		JsonObject json = new JsonObject()
			.put("prompt", prompt)
			.put("width", width)
			.put("height", height)
			.put("num_frames", numFrames)
			.put("fps", fps)
			.put("steps", steps)
			.put("guidance", guidance);
		// Only send a negative prompt when set - blank lets the sidecar apply its own default.
		if (negativePrompt != null && !negativePrompt.isBlank()) {
			json.put("negative_prompt", negativePrompt);
		}
		if (seed != null) {
			json.put("seed", seed);
		}
		return json;
	}

	private byte[] post(String endpoint, JsonObject json) {
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
				throw new RuntimeException("Video sidecar returned HTTP " + status + " for " + endpoint + ": "
					+ new String(response.body()));
			}
			return response.body();
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
			ImageIO.write(image, "png", bos);
			return Base64.getEncoder().encodeToString(bos.toByteArray());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
