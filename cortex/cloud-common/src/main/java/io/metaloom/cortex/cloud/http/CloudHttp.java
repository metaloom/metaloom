package io.metaloom.cortex.cloud.http;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.cortex.cloud.auth.CloudTokenSource;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonObject;

/**
 * The one HTTP client both cloud stores talk through.
 *
 * <p>Everything that is the same between Drive and Graph lives here: bearer injection, JSON
 * decoding, retry with backoff, and streaming a body to a file. What it deliberately does
 * <em>not</em> do is understand either API - the stores build the URLs and read the JSON.</p>
 *
 * <h2>Retry policy</h2>
 * <ul>
 * <li>{@code 429} and {@code 5xx} retry up to {@code maxRetries} with capped exponential backoff,
 * honouring {@code Retry-After} when the server sends one.</li>
 * <li><b>{@code 403} is the trap.</b> Google reports throttling as {@code 403} with a
 * {@code rateLimitExceeded} reason rather than a {@code 429}. Only those reasons are retried; a
 * genuine permission error still fails immediately. See {@link CloudApiException#isRetryable()}.</li>
 * <li>{@code 401} retries exactly <b>once</b>, after invalidating the cached token. That is the
 * only path here that can re-enter, so it is capped separately rather than folded into the retry
 * budget - a revoked credential must fail, not spin.</li>
 * </ul>
 */
public class CloudHttp {

	private static final Logger log = LoggerFactory.getLogger(CloudHttp.class);

	private static final long BASE_BACKOFF_MS = 500;
	private static final long MAX_BACKOFF_MS = 32_000;

	/** Injected so tests do not actually wait out a backoff. */
	@FunctionalInterface
	public interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}

	private final HttpClient client;
	private final CloudTokenSource tokenSource;
	private final long requestTimeoutMs;
	private final int maxRetries;
	private final Sleeper sleeper;

	public CloudHttp(CloudTokenSource tokenSource, long requestTimeoutMs, int maxRetries) {
		this(tokenSource, requestTimeoutMs, maxRetries, Thread::sleep);
	}

	public CloudHttp(CloudTokenSource tokenSource, long requestTimeoutMs, int maxRetries, Sleeper sleeper) {
		if (tokenSource == null) {
			throw new IllegalArgumentException("A token source must be provided");
		}
		this.tokenSource = tokenSource;
		this.requestTimeoutMs = Math.max(1000, requestTimeoutMs);
		this.maxRetries = Math.max(0, maxRetries);
		this.sleeper = sleeper;
		this.client = HttpClient.newBuilder()
			// HTTP/1.1 is forced across cortex; several upstreams negotiate HTTP/2 badly and the
			// resulting failures are indistinguishable from real errors.
			.version(HttpClient.Version.HTTP_1_1)
			// Graph answers /content with a 302 to a short-lived, pre-authenticated storage URL.
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(20))
			.build();
	}

	/**
	 * GET a URL and parse the response as JSON.
	 *
	 * @param url the absolute URL
	 * @return the parsed body
	 * @throws IOException on transport failure or a non-retryable API error
	 */
	public JsonObject getJson(String url) throws IOException {
		HttpResponse<String> response = send(url, true, BodyHandlers.ofString(StandardCharsets.UTF_8),
			CloudHttp::stringBody);
		try {
			return new JsonObject(response.body());
		} catch (DecodeException e) {
			throw new IOException("Response from " + url + " is not valid JSON", e);
		}
	}

	/**
	 * Stream a URL's body into a file.
	 *
	 * @param url           the absolute URL
	 * @param target        the file to write; overwritten if present
	 * @param authenticated whether to send the bearer token. A pre-authenticated download URL must
	 *                      be fetched <em>without</em> one - some storage front ends reject a
	 *                      request that carries both their signature and an unrelated bearer
	 * @throws IOException on transport failure or a non-retryable API error
	 */
	public void download(String url, Path target, boolean authenticated) throws IOException {
		send(url, authenticated,
			BodyHandlers.ofFile(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING),
			response -> readErrorFile(target));
	}

	/**
	 * @param url          the absolute URL
	 * @param authenticated whether to attach the bearer token
	 * @param handler      how to consume a successful body
	 * @param errorReader  how to recover the body text of a failed response, for the error message
	 * @return the response
	 */
	private <T> HttpResponse<T> send(String url, boolean authenticated, HttpResponse.BodyHandler<T> handler,
		ErrorReader<T> errorReader) throws IOException {

		int attempt = 0;
		boolean reauthenticated = false;

		while (true) {
			HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMillis(requestTimeoutMs))
				.header("Accept", "application/json")
				.GET();
			if (authenticated) {
				builder.header("Authorization", "Bearer " + tokenSource.accessToken());
			}

			HttpResponse<T> response;
			try {
				response = client.send(builder.build(), handler);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while calling " + url, e);
			}

			int status = response.statusCode();
			if (status < 300) {
				return response;
			}

			String body = errorReader.read(response);
			CloudApiException error = toException(status, url, body);

			if (status == 401 && authenticated && !reauthenticated) {
				// Revoked or prematurely expired: drop the cached token and try once more.
				log.debug("Got 401 from {}; refreshing the access token and retrying once", url);
				tokenSource.invalidate();
				reauthenticated = true;
				continue;
			}

			if (!error.isRetryable() || attempt >= maxRetries) {
				throw error;
			}

			long delay = backoffMillis(attempt, response.headers().firstValue("Retry-After").orElse(null));
			log.debug("Retrying {} after {} ms (attempt {} of {}, status {})", url, delay, attempt + 1, maxRetries, status);
			try {
				sleeper.sleep(delay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while backing off before retrying " + url, e);
			}
			attempt++;
		}
	}

	@FunctionalInterface
	private interface ErrorReader<T> {
		String read(HttpResponse<T> response);
	}

	private static String stringBody(HttpResponse<String> response) {
		return response.body();
	}

	/**
	 * A failed download has already streamed the error body into the target file, so that is where
	 * the message has to come from. The file is left in place; the materializer writes to a
	 * {@code .part} and deletes it on failure.
	 */
	private static String readErrorFile(Path target) {
		try {
			return Files.size(target) > 64_000 ? "" : Files.readString(target, StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			return "";
		}
	}

	/**
	 * Turn a failure body into a typed exception.
	 *
	 * <p>The two providers nest their error differently - Google under {@code error.errors[].reason},
	 * Microsoft under {@code error.code} - and both matter: the reason is what distinguishes
	 * throttling from a permission denial, and the code is what identifies an expired delta
	 * cursor.</p>
	 */
	static CloudApiException toException(int status, String url, String body) {
		String code = null;
		String message = null;
		if (body != null && !body.isBlank()) {
			try {
				JsonObject error = new JsonObject(body).getJsonObject("error");
				if (error != null) {
					code = error.getString("code");
					message = error.getString("message");
					var errors = error.getJsonArray("errors");
					if (errors != null && !errors.isEmpty()) {
						JsonObject first = errors.getJsonObject(0);
						if (first != null && first.getString("reason") != null) {
							// Google's reason is the more specific of the two; prefer it.
							code = first.getString("reason");
							if (message == null) {
								message = first.getString("message");
							}
						}
					}
				}
			} catch (DecodeException | ClassCastException e) {
				// A non-JSON error body (a proxy page, an empty 502) is still an error; the status
				// alone drives the retry decision.
				log.trace("Could not parse the error body from {}", url, e);
			}
		}
		return new CloudApiException(status, code,
			"Request to " + url + " failed with HTTP " + status
				+ (code == null ? "" : " (" + code + ")")
				+ (message == null ? "" : ": " + message));
	}

	/**
	 * @param attempt    zero-based retry attempt
	 * @param retryAfter the {@code Retry-After} header, in seconds or as an HTTP date
	 * @return how long to wait
	 */
	static long backoffMillis(int attempt, String retryAfter) {
		Long honoured = parseRetryAfter(retryAfter);
		if (honoured != null) {
			return Math.min(honoured, MAX_BACKOFF_MS);
		}
		long exponential = Math.min(BASE_BACKOFF_MS << Math.min(attempt, 16), MAX_BACKOFF_MS);
		// Jitter keeps a fleet of workers that hit the same limit from retrying in lockstep.
		long jitter = (long) (exponential * 0.2 * Math.random());
		return exponential + jitter;
	}

	private static Long parseRetryAfter(String header) {
		if (header == null || header.isBlank()) {
			return null;
		}
		String value = header.trim();
		try {
			return Long.parseLong(value) * 1000;
		} catch (NumberFormatException ignored) {
			// Fall through to the HTTP-date form.
		}
		try {
			ZonedDateTime when = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
			long delta = when.toInstant().toEpochMilli() - System.currentTimeMillis();
			return delta > 0 ? delta : 0L;
		} catch (DateTimeParseException e) {
			return null;
		}
	}
}
