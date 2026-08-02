package io.metaloom.cortex.cloud.auth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import io.metaloom.cortex.cloud.http.CloudApiException;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;

/**
 * The form-encoded token POST that all four OAuth grants share.
 *
 * <p>One place rather than four, because the differences between a Google service-account
 * assertion, a Google refresh token, a Microsoft client-credentials grant and a Microsoft refresh
 * token are entirely in the form fields - the transport, the error handling and the JSON shape are
 * identical.</p>
 */
public final class TokenEndpoint {

	/**
	 * HTTP/1.1 is forced here for the same reason as everywhere else in cortex: several of the
	 * services we talk to negotiate HTTP/2 badly, and a token request failing is indistinguishable
	 * from bad credentials.
	 */
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(Duration.ofSeconds(20))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	private TokenEndpoint() {
	}

	/**
	 * POST a form to a token endpoint and return the parsed response.
	 *
	 * @param tokenUrl  the endpoint
	 * @param form      the form fields
	 * @param timeoutMs request timeout in milliseconds
	 * @return the token response
	 * @throws IOException when the endpoint is unreachable or rejects the grant
	 */
	public static JsonObject post(String tokenUrl, Map<String, String> form, long timeoutMs) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
			.timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.header("Accept", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8))
			.build();

		HttpResponse<String> response;
		try {
			response = CLIENT.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while requesting a token from " + tokenUrl, e);
		}

		if (response.statusCode() >= 300) {
			throw new CloudApiException(response.statusCode(), errorCodeOf(response.body()),
				"Token request to " + tokenUrl + " failed with HTTP " + response.statusCode() + ": "
					+ describe(response.body()));
		}

		JsonObject body = parse(response.body());
		if (body == null || body.getString("access_token") == null) {
			throw new IOException("Token endpoint " + tokenUrl + " returned no access_token");
		}
		return body;
	}

	private static String encode(Map<String, String> form) {
		StringBuilder builder = new StringBuilder();
		for (Map.Entry<String, String> entry : form.entrySet()) {
			if (entry.getValue() == null) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append('&');
			}
			builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
				.append('=')
				.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
		}
		return builder.toString();
	}

	private static JsonObject parse(String body) {
		if (body == null || body.isBlank()) {
			return null;
		}
		try {
			return new JsonObject(body);
		} catch (DecodeException e) {
			return null;
		}
	}

	/**
	 * Both providers answer a rejected grant with an {@code error} field; surfacing it is what
	 * turns "token request failed" into "invalid_grant", which is the difference between a useful
	 * log line and a support ticket.
	 */
	private static String errorCodeOf(String body) {
		JsonObject json = parse(body);
		return json == null ? null : json.getString("error");
	}

	private static String describe(String body) {
		JsonObject json = parse(body);
		if (json == null) {
			return body == null || body.isBlank() ? "(no body)" : truncate(body);
		}
		String error = json.getString("error");
		String description = json.getString("error_description");
		if (error == null && description == null) {
			return truncate(json.encode());
		}
		return error + (description == null ? "" : " - " + description);
	}

	private static String truncate(String value) {
		return value.length() <= 500 ? value : value.substring(0, 500) + "...";
	}
}
