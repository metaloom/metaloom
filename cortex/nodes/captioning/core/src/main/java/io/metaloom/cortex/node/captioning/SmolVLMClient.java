package io.metaloom.cortex.node.captioning;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.apache.commons.codec.binary.Base64OutputStream;
import org.imgscalr.Scalr;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import io.metaloom.video4j.utils.ImageUtils;
import io.vertx.core.json.JsonObject;

public class SmolVLMClient {

	/** Budget for one caption, including a cold sidecar still loading its weights. */
	private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

	private final String host;
	private final int port;

	// It is important to switch to HTTP_1_1. Otherwise FastAPI will not accept the request.
	private final HttpClient client = HttpClient.newBuilder()
		.version(Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(30))
		.build();

	private String prompt = null;

	public SmolVLMClient(String host, int port) {
		this.host = host;
		this.port = port;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public String captionByImageData(String base64) throws URISyntaxException {
		JsonObject json = new JsonObject();
		if (prompt != null) {
			json.put("prompt", prompt);
		}
		json.put("image_data", base64);
		return caption(json);
	}

	public String captionByURL(String url) throws URISyntaxException {
		JsonObject json = new JsonObject();
		if (prompt != null) {
			json.put("prompt", prompt);
		}
		json.put("image_url", url);
		return caption(json);
	}

	private String caption(JsonObject json) throws URISyntaxException {
		URI uri = new URI("http://" + host + ":" + port + "/caption");
		String jsonStr = json.encode();
		HttpRequest request = HttpRequest.newBuilder()
			.uri(uri)
			// Without a timeout a hung sidecar blocked the worker thread forever.
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonStr, StandardCharsets.UTF_8))
			.build();

		try {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
			// A non-2xx answer is an error, not a caption. It used to be returned as the caption text,
			// so a 500 page or a 422 validation body was cached, emitted and persisted as SUCCESS.
			if (response.statusCode() / 100 != 2) {
				throw new RuntimeException("Caption request to " + uri + " failed (HTTP " + response.statusCode() + "): " + truncate(response.body()));
			}
			return response.body();
		} catch (IOException e) {
			throw new RuntimeException("Caption request to " + uri + " failed", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException("Caption request to " + uri + " was interrupted", e);
		}

	}

	private static String truncate(String s) {
		if (s == null) {
			return "null";
		}
		return s.length() > 500 ? s.substring(0, 500) + "..." : s;
	}

	public String captionByImage(BufferedImage image, int targetSize) throws URISyntaxException, IOException {
		BufferedImage lowRes = Scalr.resize(image, targetSize);

		ByteArrayOutputStream bos = new ByteArrayOutputStream();

		// Save the resized image as JPG into the stream
		try (Base64OutputStream os = new Base64OutputStream(bos)) {
			ImageUtils.saveJPG(os, lowRes);
		}

		// Get the encoded bytes from the ByteArrayOutputStream
		byte[] encodedBytes = bos.toByteArray();

		// Convert the encoded bytes to a string
		String encodedString = new String(encodedBytes, StandardCharsets.UTF_8);

		return captionByImageData(encodedString);
	}
}
