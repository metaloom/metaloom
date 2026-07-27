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

import javax.imageio.ImageIO;

import io.vertx.core.json.JsonObject;

/**
 * HTTP client for the image-generation sidecar (see {@code sidecars/ideogram-sidecar}).
 * The Java {@link ImageGenNode} is a pure HTTP client of this model server, mirroring
 * the {@code SmolVLMClient} used by the captioning node.
 *
 * <p>
 * Two model-agnostic endpoints are called, both returning {@code image/png} bytes:
 * {@code POST /generate} (text-to-image) and {@code POST /remix} (image-to-image).
 * The connection is forced to HTTP/1.1 because the FastAPI sidecar rejects HTTP/2.
 * </p>
 */
public class ImageGenClient {

	private final String host;
	private final int port;
	private final String generateEndpoint;
	private final String remixEndpoint;
	private final int timeoutMs;

	public ImageGenClient(String host, int port, String generateEndpoint, String remixEndpoint, int timeoutMs) {
		this.host = host;
		this.port = port;
		this.generateEndpoint = generateEndpoint;
		this.remixEndpoint = remixEndpoint;
		this.timeoutMs = timeoutMs;
	}

	/**
	 * Text-to-image: generate a new image from the prompt. Returns the raw PNG bytes.
	 */
	public byte[] generate(String prompt, int width, int height, Integer seed, int steps) {
		JsonObject json = new JsonObject()
			.put("prompt", prompt)
			.put("width", width)
			.put("height", height)
			.put("steps", steps);
		if (seed != null) {
			json.put("seed", seed);
		}
		return post(generateEndpoint, json);
	}

	/**
	 * Image-to-image / remix: transform the source image guided by the prompt. Returns the raw PNG bytes.
	 */
	public byte[] remix(BufferedImage source, String prompt, double strength, Integer seed, int steps) {
		JsonObject json = new JsonObject()
			.put("image_b64", toBase64Png(source))
			.put("prompt", prompt)
			.put("strength", strength)
			.put("steps", steps);
		if (seed != null) {
			json.put("seed", seed);
		}
		return post(remixEndpoint, json);
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
				throw new RuntimeException("Image sidecar returned HTTP " + status + " for " + endpoint + ": "
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
