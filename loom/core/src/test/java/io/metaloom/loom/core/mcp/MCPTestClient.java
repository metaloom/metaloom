package io.metaloom.loom.core.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.metaloom.loom.core.dagger.LoomCoreComponent;
import io.metaloom.loom.mcp.MCPService;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;

/**
 * Test helper that issues requests against a running {@link MCPService} across all three transports
 * (message POST, SSE GET, WebSocket) with optional authentication credentials.
 *
 * <p>Mirrors the JSON-RPC-over-HTTP approach used by {@code MCPServerToolCallTest}, but adds header /
 * query-parameter credential injection and exposes the raw status codes / close codes so the auth
 * tests can assert accept vs. reject behavior.</p>
 */
public class MCPTestClient {

	private static final long TIMEOUT_SECONDS = 15;

	private final Vertx vertx;
	private final String host;
	private final int port;

	public MCPTestClient(LoomCoreComponent internal) {
		MCPService mcp = internal.boot().getMcpService();
		this.vertx = mcp.vertx();
		this.host = "localhost";
		this.port = mcp.getServer().actualPort();
	}

	// ---------------------------------------------------------------
	// JSON-RPC request builder
	// ---------------------------------------------------------------

	public static JsonObject jsonRpc(String method, Object id, JsonObject params) {
		JsonObject request = new JsonObject()
			.put("jsonrpc", "2.0")
			.put("method", method);
		if (id != null) {
			request.put("id", id);
		}
		if (params != null) {
			request.put("params", params);
		}
		return request;
	}

	public static JsonObject toolCall(String toolName, JsonObject arguments) {
		return new JsonObject()
			.put("name", toolName)
			.put("arguments", arguments == null ? new JsonObject() : arguments);
	}

	// ---------------------------------------------------------------
	// Transport: message (POST /mcp/message)
	// ---------------------------------------------------------------

	public HttpResult postMessage(JsonObject rpcRequest) throws Exception {
		return postMessage(rpcRequest, Map.of());
	}

	/**
	 * POST a JSON-RPC request to {@code /mcp/message} with the supplied headers.
	 */
	public HttpResult postMessage(JsonObject rpcRequest, Map<String, String> headers) throws Exception {
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<HttpResult> future = new CompletableFuture<>();
		client.request(HttpMethod.POST, port, host, "/mcp/message")
			.compose(req -> {
				req.putHeader("Content-Type", "application/json");
				headers.forEach(req::putHeader);
				return req.send(Buffer.buffer(rpcRequest.encode()));
			})
			.compose(resp -> resp.body().map(body -> new HttpResult(resp.statusCode(),
				resp.getHeader("Access-Control-Allow-Origin"), body.toString())))
			.onSuccess(future::complete)
			.onFailure(future::completeExceptionally);
		try {
			return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			client.close();
		}
	}

	public HttpResult postMessageWithBearer(JsonObject rpcRequest, String jwt) throws Exception {
		return postMessage(rpcRequest, Map.of("Authorization", "Bearer " + jwt));
	}

	public HttpResult postMessageWithApiKey(JsonObject rpcRequest, String apiKey) throws Exception {
		return postMessage(rpcRequest, Map.of("X-API-Key", apiKey));
	}

	// ---------------------------------------------------------------
	// Transport: SSE (GET /mcp/sse)
	// ---------------------------------------------------------------

	/**
	 * Open the SSE endpoint and return the status code, the {@code Access-Control-Allow-Origin} header
	 * and (on a 200 response) the first streamed event chunk (the {@code endpoint} event).
	 *
	 * @param token   optional token supplied via {@code ?token=} (may be null)
	 * @param headers additional request headers (e.g. Authorization, Origin)
	 */
	public SseResult openSse(String token, Map<String, String> headers) throws Exception {
		String uri = "/mcp/sse";
		if (token != null) {
			uri += "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
		}
		HttpClient client = vertx.createHttpClient();
		CompletableFuture<SseResult> future = new CompletableFuture<>();
		client.request(HttpMethod.GET, port, host, uri)
			.compose(req -> {
				headers.forEach(req::putHeader);
				return req.send();
			})
			.onSuccess(resp -> {
				int status = resp.statusCode();
				String acao = resp.getHeader("Access-Control-Allow-Origin");
				String acac = resp.getHeader("Access-Control-Allow-Credentials");
				if (status != 200) {
					future.complete(new SseResult(status, acao, acac, null));
					return;
				}
				// Streaming response: capture the first chunk (the endpoint event) then resolve.
				resp.handler(buf -> {
					if (!future.isDone()) {
						future.complete(new SseResult(status, acao, acac, buf.toString()));
					}
				});
				resp.exceptionHandler(err -> {
					if (!future.isDone()) {
						future.completeExceptionally(err);
					}
				});
			})
			.onFailure(future::completeExceptionally);
		try {
			return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			client.close();
		}
	}

	public SseResult openSseWithToken(String token) throws Exception {
		return openSse(token, Map.of());
	}

	public SseResult openSseWithOrigin(String token, String origin) throws Exception {
		Map<String, String> headers = new HashMap<>();
		headers.put("Origin", origin);
		return openSse(token, headers);
	}

	// ---------------------------------------------------------------
	// Transport: WebSocket (GET /mcp/ws)
	// ---------------------------------------------------------------

	/**
	 * Connect to {@code /mcp/ws}, send a single JSON-RPC frame and return either the response frame or
	 * the WebSocket close code (e.g. {@code 4401} when authentication fails).
	 *
	 * @param token optional token supplied via {@code ?token=} (may be null)
	 */
	public WsResult wsRoundTrip(String token, JsonObject rpcRequest) throws Exception {
		String uri = "/mcp/ws";
		if (token != null) {
			uri += "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
		}
		WebSocketClient client = vertx.createWebSocketClient();
		CompletableFuture<WsResult> future = new CompletableFuture<>();
		WebSocketConnectOptions opts = new WebSocketConnectOptions()
			.setHost(host)
			.setPort(port)
			.setURI(uri);
		client.connect(opts)
			.onSuccess(ws -> {
				ws.closeHandler(v -> {
					if (!future.isDone()) {
						future.complete(new WsResult(null, (int) ws.closeStatusCode()));
					}
				});
				ws.textMessageHandler(text -> {
					if (!future.isDone()) {
						future.complete(new WsResult(new JsonObject(text), null));
					}
				});
				ws.exceptionHandler(err -> {
					if (!future.isDone()) {
						future.completeExceptionally(err);
					}
				});
				// The server installs its message handler only after the async authentication step
				// completes. Delay the first frame slightly to avoid a race where it is sent before the
				// handler exists. On an auth failure the server closes the socket (4401) before this
				// fires, so guard against writing to a closed connection.
				vertx.setTimer(300, t -> {
					if (!future.isDone()) {
						try {
							ws.writeTextMessage(rpcRequest.encode());
						} catch (Exception e) {
							// Connection already closed (e.g. 4401) - the closeHandler resolves the future.
						}
					}
				});
			})
			.onFailure(future::completeExceptionally);
		try {
			return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} finally {
			client.close();
		}
	}

	// ---------------------------------------------------------------
	// Result records
	// ---------------------------------------------------------------

	/**
	 * Result of a message-endpoint request.
	 *
	 * @param statusCode      the HTTP status code
	 * @param allowOrigin     the {@code Access-Control-Allow-Origin} response header (may be null)
	 * @param body            the raw response body
	 */
	public record HttpResult(int statusCode, String allowOrigin, String body) {
		public JsonObject json() {
			return new JsonObject(body);
		}
	}

	/**
	 * Result of opening the SSE endpoint.
	 *
	 * @param statusCode       the HTTP status code
	 * @param allowOrigin      the {@code Access-Control-Allow-Origin} response header (may be null)
	 * @param allowCredentials the {@code Access-Control-Allow-Credentials} response header (may be null)
	 * @param firstEvent       the first streamed SSE chunk on success (may be null)
	 */
	public record SseResult(int statusCode, String allowOrigin, String allowCredentials, String firstEvent) {
	}

	/**
	 * Result of a WebSocket round-trip: exactly one of {@code response} / {@code closeCode} is set.
	 *
	 * @param response  the JSON-RPC response frame (null when the connection was closed instead)
	 * @param closeCode the WebSocket close status code (null when a response frame was received)
	 */
	public record WsResult(JsonObject response, Integer closeCode) {
	}
}
