package io.metaloom.loom.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.metaloom.loom.core.LoomCoreTestExtension;
import io.metaloom.loom.core.dagger.LoomCoreComponent;
import io.metaloom.loom.core.mcp.MCPTestClient.HttpResult;
import io.metaloom.loom.core.mcp.MCPTestClient.SseResult;
import io.metaloom.loom.core.mcp.MCPTestClient.WsResult;
import io.vertx.core.json.JsonObject;

/**
 * MCP authentication behavior with auth <strong>enabled in strict mode</strong>
 * ({@code mcpAuthEnabled=true}, {@code mcpAuthStrictMode=true}) and a restricted allowed-origins list.
 *
 * <p>Strict mode rejects HTTP requests that supply no credentials (401), and a supplied-but-invalid
 * token is rejected on every transport. One transport nuance this suite accounts for:</p>
 * <ul>
 *   <li>WebSocket strictness is governed by {@code LOOM_WS_STRICT_AUTH} (default lenient), not by
 *       {@code mcpAuthStrictMode}. An invalid WS token is always rejected with close code 4401, so the
 *       WS rejection test uses an invalid token rather than a missing one.</li>
 * </ul>
 */
public class MCPAuthStrictTest {

	private static final String ALLOWED_ORIGIN = "https://example.com";

	@RegisterExtension
	LoomCoreTestExtension loom = new LoomCoreTestExtension()
		.withOptions(o -> {
			o.getAuth().setMcpAuthEnabled(true);
			o.getAuth().setMcpAuthStrictMode(true);
			o.getAuth().setMcpAuthAllowedOrigins(ALLOWED_ORIGIN);
		});

	private LoomCoreComponent internal() {
		return loom.internal();
	}

	// ── Message endpoint ──────────────────────────────────────────────

	@Test
	public void testMessageMissingCredentialsRejected() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());

		HttpResult result = client.postMessage(MCPAuthTestSupport.searchAssetsCall(1));

		assertEquals(401, result.statusCode(), "Strict mode must reject uncredentialed message requests");
	}

	@Test
	public void testMessageValidJwtSucceeds() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		HttpResult result = client.postMessageWithBearer(MCPAuthTestSupport.searchAssetsCall(2), jwt);

		assertEquals(200, result.statusCode());
		JsonObject response = result.json();
		assertNull(response.getJsonObject("error"));
		assertNotNull(response.getJsonObject("result"));
	}

	@Test
	public void testMessageInvalidTokenRejected() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());

		// A supplied-but-invalid Bearer token matches neither a valid JWT nor a known API key, so it is
		// rejected with 401 rather than falling through to anonymous access.
		HttpResult result = client.postMessageWithBearer(MCPAuthTestSupport.searchAssetsCall(3), "not-a-real-token");

		assertEquals(401, result.statusCode(), "An invalid token must be rejected, not treated as anonymous");
	}

	// ── SSE endpoint ──────────────────────────────────────────────────

	@Test
	public void testSseMissingTokenRejected() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());

		SseResult result = client.openSseWithToken(null);

		assertEquals(401, result.statusCode(), "Strict mode must reject SSE connections without a token");
	}

	@Test
	public void testSseValidTokenAccepted() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		SseResult result = client.openSseWithToken(jwt);

		assertEquals(200, result.statusCode());
		assertNotNull(result.firstEvent());
		assertTrue(result.firstEvent().contains("endpoint"));
	}

	// ── WebSocket endpoint ────────────────────────────────────────────

	@Test
	public void testWebSocketInvalidTokenClosed4401() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());

		WsResult result = client.wsRoundTrip("not-a-real-token", MCPAuthTestSupport.toolsListRequest(4));

		assertNull(result.response(), "No JSON-RPC response should be produced for an invalid WS token");
		assertEquals(4401, result.closeCode(), "Invalid WS token must be rejected with close code 4401");
	}

	@Test
	public void testWebSocketValidTokenRoundTrip() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		WsResult result = client.wsRoundTrip(jwt, MCPAuthTestSupport.toolsListRequest(5));

		assertNotNull(result.response(), "A valid WS token should yield a JSON-RPC response");
		assertEquals("2.0", result.response().getString("jsonrpc"));
		assertNotNull(result.response().getJsonObject("result").getJsonArray("tools"));
	}

	// ── CORS ──────────────────────────────────────────────────────────

	@Test
	public void testCorsAllowedOriginEchoed() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		SseResult result = client.openSseWithOrigin(jwt, ALLOWED_ORIGIN);

		assertEquals(200, result.statusCode());
		assertEquals(ALLOWED_ORIGIN, result.allowOrigin(), "An allowed origin must be echoed back");
		assertEquals("true", result.allowCredentials());
	}

	@Test
	public void testCorsDisallowedOriginNotEchoed() throws Exception {
		MCPTestClient client = new MCPTestClient(internal());
		String jwt = MCPAuthTestSupport.adminJwt(internal());

		SseResult result = client.openSseWithOrigin(jwt, "https://evil.example.org");

		// The origin is not permitted and the allow-list is not a wildcard, so no CORS origin header is
		// returned (the browser would block the response). The request itself is still authenticated.
		assertEquals(200, result.statusCode());
		assertNull(result.allowOrigin(), "A disallowed origin must not receive an Access-Control-Allow-Origin header");
	}
}
