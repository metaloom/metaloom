package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.vertx.core.json.JsonObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The {@code X-Trace-Id} contract.
 *
 * <p>
 * Driven over raw HTTP rather than through {@code LoomHttpClient}, because the thing under test is a response <i>header</i> and the typed client
 * deliberately hands back only the parsed body. A test that could only see the body would pass while the header - which is the half the browser reads
 * on a successful response, and the half CORS can silently hide - was missing entirely.
 * </p>
 */
public class TraceIdEndpointTest extends AbstractEndpointTest {

	private static final String TRACE_HEADER = "X-Trace-Id";

	private final OkHttpClient http = new OkHttpClient();

	@Test
	public void testEveryResponseCarriesATraceId() throws Exception {
		try (Response response = get("/api/v1/info")) {
			String traceId = response.header(TRACE_HEADER);
			assertNotNull(traceId, "Every response must carry a trace id, not only the failures");
			assertEquals(32, traceId.length(), "Generated ids are 16 random bytes, hex encoded");
		}
	}

	@Test
	public void testTwoRequestsGetDifferentIds() throws Exception {
		String first;
		String second;
		try (Response response = get("/api/v1/info")) {
			first = response.header(TRACE_HEADER);
		}
		try (Response response = get("/api/v1/info")) {
			second = response.header(TRACE_HEADER);
		}
		assertNotEquals(first, second, "A trace id names one request; reusing it would make the log unjoinable");
	}

	/**
	 * The pairing the whole feature depends on: the id in the error body is the id in the header, so a user who can
	 * only see the JSON and an operator who can only see the header are talking about the same request.
	 */
	@Test
	public void testErrorBodyCarriesTheSameTraceIdAsTheHeader() throws Exception {
		try (Response response = get("/api/v1/this-route-does-not-exist")) {
			assertEquals(404, response.code());
			String header = response.header(TRACE_HEADER);
			assertNotNull(header);

			JsonObject body = new JsonObject(response.body().string());
			assertEquals(header, body.getString("traceId"), "The body copy and the header must be the same id");
		}
	}

	/**
	 * A caller that already has an id keeps it across the hop, so a trace started by the CLI or by cortex does not
	 * break at the REST boundary.
	 */
	@Test
	public void testAnInboundTraceIdIsHonoured() throws Exception {
		try (Response response = get("/api/v1/info", "cortex-run-4711")) {
			assertEquals("cortex-run-4711", response.header(TRACE_HEADER));
		}
	}

	/**
	 * ...but never verbatim. The id is written into log lines, so a value carrying a newline would let a caller forge
	 * log records. A rejected header produces a fresh id rather than an error: a malformed trace header is not a
	 * reason to fail somebody's request.
	 */
	@Test
	public void testAForgedTraceIdIsReplacedRatherThanEchoed() throws Exception {
		// okhttp refuses to send a header containing a raw newline at all, which is itself one layer of the defence.
		// The characters below are ones it will send and the handler must still refuse.
		try (Response response = get("/api/v1/info", "not a valid <trace> id")) {
			String traceId = response.header(TRACE_HEADER);
			assertNotEquals("not a valid <trace> id", traceId, "A trace id containing spaces or markup must not be echoed back");
			assertEquals(32, traceId.length(), "A rejected inbound id is replaced by a generated one");
		}

		// Too long is also refused - the cap keeps a caller from stuffing a payload into every log line.
		String overlong = "a".repeat(65);
		try (Response response = get("/api/v1/info", overlong)) {
			assertNotEquals(overlong, response.header(TRACE_HEADER));
		}
	}

	/**
	 * Cross-origin is the deployment the UI is developed on (loom-ui on :3000, Loom on :8080). Without an explicit
	 * expose-headers the browser hides every response header from JS but the safelisted six, so the trace id would
	 * read as null in exactly the setup where it is needed most.
	 */
	@Test
	public void testTheTraceHeaderIsExposedToCrossOriginCallers() throws Exception {
		Request request = new Request.Builder()
			.url(baseUrl() + "/api/v1/info")
			.header("Origin", "http://localhost:3000")
			.build();
		try (Response response = http.newCall(request).execute()) {
			String exposed = response.header("Access-Control-Expose-Headers");
			assertNotNull(exposed, "CORS must expose headers, or the browser hides the trace id from the UI");
			assertTrue(exposed.toLowerCase().contains("x-trace-id"), "Expected X-Trace-Id among the exposed headers, got: " + exposed);
		}
	}

	private Response get(String path) throws IOException {
		return get(path, null);
	}

	private Response get(String path, String inboundTraceId) throws IOException {
		Request.Builder builder = new Request.Builder().url(baseUrl() + path);
		if (inboundTraceId != null) {
			builder.header(TRACE_HEADER, inboundTraceId);
		}
		return http.newCall(builder.build()).execute();
	}

	private String baseUrl() {
		return "http://localhost:" + loom.internal().boot().getRestService().getServer().actualPort();
	}
}
