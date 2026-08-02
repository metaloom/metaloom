package io.metaloom.cortex.cloud.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.cloud.StubHttpServer;
import io.metaloom.cortex.cloud.StubHttpServer.Response;
import io.metaloom.cortex.cloud.auth.CloudTokenSource;
import io.vertx.core.json.JsonObject;

public class CloudHttpTest {

	@TempDir
	Path tempDir;

	private StubHttpServer server;
	private RecordingTokenSource tokens;
	private final List<Long> sleeps = new ArrayList<>();

	private static class RecordingTokenSource implements CloudTokenSource {
		final AtomicInteger invalidations = new AtomicInteger();
		int issued;

		@Override
		public String accessToken() {
			return "token-" + (++issued);
		}

		@Override
		public void invalidate() {
			invalidations.incrementAndGet();
		}

		@Override
		public String accountId() {
			return "test";
		}
	}

	@BeforeEach
	public void setup() throws IOException {
		server = new StubHttpServer();
		tokens = new RecordingTokenSource();
		sleeps.clear();
	}

	@AfterEach
	public void teardown() {
		server.close();
	}

	private CloudHttp http(int maxRetries) {
		return new CloudHttp(tokens, 5000, maxRetries, sleeps::add);
	}

	@Test
	public void testGetJsonReturnsTheParsedBody() throws IOException {
		server.enqueueJson("{\"value\":42}");
		assertThat(http(0).getJson(server.baseUrl() + "/x").getInteger("value")).isEqualTo(42);
	}

	@Test
	public void testRetriesOn429HonouringRetryAfter() throws IOException {
		server.enqueue(Response.error(429, "{}").withHeader("Retry-After", "3"));
		server.enqueueJson("{\"ok\":true}");

		assertThat(http(3).getJson(server.baseUrl() + "/x").getBoolean("ok")).isTrue();
		assertThat(sleeps).containsExactly(3000L);
	}

	@Test
	public void testRetriesOn503() throws IOException {
		server.enqueue(Response.error(503, ""));
		server.enqueueJson("{\"ok\":true}");

		assertThat(http(3).getJson(server.baseUrl() + "/x").getBoolean("ok")).isTrue();
		assertThat(server.requestCount()).isEqualTo(2);
	}

	/**
	 * The trap this whole class exists for: Google reports throttling as a 403 with a
	 * {@code rateLimitExceeded} reason, not a 429. Only retrying 429 makes Drive throttling look
	 * like a permission failure and kills the run.
	 */
	@Test
	public void testRetriesOnGoogles403RateLimitExceeded() throws IOException {
		server.enqueue(Response.error(403, googleError("userRateLimitExceeded", "Rate Limit Exceeded")));
		server.enqueueJson("{\"ok\":true}");

		assertThat(http(3).getJson(server.baseUrl() + "/x").getBoolean("ok")).isTrue();
		assertThat(server.requestCount()).isEqualTo(2);
	}

	@Test
	public void testDoesNotRetryAGenuine403() {
		server.fallback(Response.error(403, googleError("insufficientPermissions", "Insufficient Permission")));

		assertThatThrownBy(() -> http(3).getJson(server.baseUrl() + "/x"))
			.isInstanceOf(CloudApiException.class)
			.hasMessageContaining("insufficientPermissions");
		assertThat(server.requestCount()).isEqualTo(1);
	}

	@Test
	public void testA401RefreshesTheTokenAndRetriesExactlyOnce() {
		server.fallback(Response.error(401, "{}"));

		assertThatThrownBy(() -> http(3).getJson(server.baseUrl() + "/x"))
			.isInstanceOf(CloudApiException.class);

		// One retry, not a loop: a revoked credential must fail rather than spin.
		assertThat(tokens.invalidations).hasValue(1);
		assertThat(server.requestCount()).isEqualTo(2);
	}

	@Test
	public void testGivesUpAfterMaxRetries() {
		server.fallback(Response.error(503, ""));

		assertThatThrownBy(() -> http(2).getJson(server.baseUrl() + "/x"))
			.isInstanceOf(CloudApiException.class);
		assertThat(server.requestCount()).isEqualTo(3);
		assertThat(sleeps).hasSize(2);
	}

	@Test
	public void testBackoffIsCappedAndExponential() {
		assertThat(CloudHttp.backoffMillis(0, null)).isBetween(500L, 700L);
		assertThat(CloudHttp.backoffMillis(1, null)).isBetween(1000L, 1300L);
		assertThat(CloudHttp.backoffMillis(30, null)).isLessThanOrEqualTo(40_000L);
	}

	@Test
	public void testRetryAfterAsAnHttpDateIsHonoured() {
		String header = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
			.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(5));
		assertThat(CloudHttp.backoffMillis(0, header)).isBetween(1L, 6000L);
	}

	@Test
	public void testTheErrorBodysReasonBeatsItsCode() {
		CloudApiException error = CloudHttp.toException(403, "http://x",
			googleError("rateLimitExceeded", "Rate Limit Exceeded"));

		assertThat(error.errorCode()).isEqualTo("rateLimitExceeded");
		assertThat(error.isRetryable()).isTrue();
	}

	@Test
	public void testAGoneWithResyncRequiredIsADeltaExpiry() {
		CloudApiException error = CloudHttp.toException(410, "http://x",
			new JsonObject().put("error", new JsonObject().put("code", "resyncRequired")).encode());

		assertThat(error.isDeltaTokenExpired()).isTrue();
	}

	@Test
	public void testANonJsonErrorBodyIsStillAnError() {
		CloudApiException error = CloudHttp.toException(502, "http://x", "<html>bad gateway</html>");

		assertThat(error.status()).isEqualTo(502);
		assertThat(error.isRetryable()).isTrue();
	}

	@Test
	public void testDownloadStreamsToTheTargetFile() throws IOException {
		server.enqueue(Response.ok("payload"));
		Path target = tempDir.resolve("out.bin");

		http(0).download(server.baseUrl() + "/content", target, true);

		assertThat(Files.readString(target)).isEqualTo("payload");
	}

	@Test
	public void testAnUnauthenticatedDownloadSendsNoBearer() throws IOException {
		// Graph's pre-authenticated download URL rejects an unrelated bearer, so the flag has to
		// actually suppress the header.
		server.enqueue(Response.ok("payload"));
		http(0).download(server.baseUrl() + "/preauth", tempDir.resolve("out.bin"), false);

		assertThat(tokens.issued).isZero();
	}

	private static String googleError(String reason, String message) {
		return new JsonObject().put("error", new JsonObject()
			.put("code", 403)
			.put("message", message)
			.put("errors", new io.vertx.core.json.JsonArray()
				.add(new JsonObject().put("reason", reason).put("message", message))))
			.encode();
	}
}
