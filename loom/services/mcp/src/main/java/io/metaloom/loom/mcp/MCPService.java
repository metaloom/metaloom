package io.metaloom.loom.mcp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.auth.LoomAuthenticationHandler;
import io.metaloom.loom.auth.MCPAuthenticationHandler;
import io.metaloom.loom.rest.service.impl.WebSocketAuthenticator;
import io.metaloom.loom.common.service.AbstractService;
import io.metaloom.loom.mcp.handler.MCPJsonRpcHandler;
import io.metaloom.loom.mcp.model.JsonRpcRequest;
import io.metaloom.loom.mcp.model.JsonRpcResponse;
import io.metaloom.loom.mcp.tool.MCPToolRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * MCP (Model Context Protocol) server service.
 *
 * <p>Provides two transports for MCP clients:</p>
 * <ol>
 *   <li><strong>HTTP + SSE (Streamable HTTP)</strong> — The MCP 2025-03-26 standard transport.
 *       Client opens SSE on {@code /mcp/sse} and sends JSON-RPC via POST to {@code /mcp/message}.</li>
 *   <li><strong>WebSocket</strong> — Client connects to {@code /mcp/ws} and sends/receives
 *       JSON-RPC frames directly. This enables bidirectional streaming and is particularly
 *       useful for tools that produce incremental results.</li>
 * </ol>
 *
 * <h2>Internal dispatch via Vert.x EventBus</h2>
 * <p>Tool calls received over either transport are dispatched through the Vert.x EventBus.
 * Each registered {@link io.metaloom.loom.mcp.tool.MCPTool} listens on address
 * {@code mcp.tool.<name>}. This architecture was chosen because:</p>
 * <ul>
 *   <li>It decouples transport from tool logic — tools don't know about HTTP/WS.</li>
 *   <li>Other Loom services can register tools by simply publishing an EventBus consumer.</li>
 *   <li>In a clustered Vert.x deployment, tools can run on different nodes.</li>
 *   <li>The EventBus provides natural back-pressure and request/reply semantics.</li>
 * </ul>
 *
 * <h2>Authentication</h2>
 * <p>Authentication can be enabled via {@code LOOM_MCP_AUTH_ENABLED} and supports:</p>
 * <ul>
 *   <li><strong>SSE (/mcp/sse)</strong>: Token via {@code ?token=} query parameter OR {@code Authorization: Bearer} header</li>
 *   <li><strong>Message (/mcp/message)</strong>: Token via {@code Authorization: Bearer} header OR {@code X-API-Key} header</li>
 *   <li><strong>WebSocket (/mcp/ws)</strong>: Token via {@code ?token=} query parameter (handled by {@link WebSocketAuthenticator})</li>
 * </ul>
 */
@Singleton
public class MCPService extends AbstractService {

	private static final Logger log = LoggerFactory.getLogger(MCPService.class);

	private HttpServer server;

	private final MCPJsonRpcHandler jsonRpcHandler;
	private final MCPToolRegistry toolRegistry;
	private final MCPAuthenticationHandler mcpAuthHandler;
	private final WebSocketAuthenticator wsAuthenticator;

	/**
	 * Connected SSE sessions: sessionId → response (kept open for server-sent events).
	 */
	private final Map<String, HttpServerResponse> sseSessions = new ConcurrentHashMap<>();

	@Inject
	public MCPService(Vertx vertx, LoomOptions options, MCPJsonRpcHandler jsonRpcHandler, 
			MCPToolRegistry toolRegistry, MCPAuthenticationHandler mcpAuthHandler, 
			WebSocketAuthenticator wsAuthenticator) {
		super(vertx, options);
		this.jsonRpcHandler = jsonRpcHandler;
		this.toolRegistry = toolRegistry;
		this.mcpAuthHandler = mcpAuthHandler;
		this.wsAuthenticator = wsAuthenticator;
	}

	/**
	 * Start the MCP HTTP server on the configured port
	 * ({@code server.mcpPort} / {@code LOOM_SERVER_MCP_PORT}, default 4041).
	 * When the REST server port in the options is set to 0 (test mode),
	 * the MCP server will also use port 0 (OS-assigned).
	 */
	public HttpServer start() throws InterruptedException, ExecutionException {
		int port = options().getServer().getRestPort() == 0 ? 0 : options().getServer().getMcpPort();
		return start(port);
	}

	/**
	 * Start the MCP HTTP server on the specified port.
	 *
	 * @param port the port to listen on (use 0 for an OS-assigned random port)
	 */
	public HttpServer start(int port) throws InterruptedException, ExecutionException {
		String host = options().getServer().getBindAddress();

		log.info("Starting MCP server on {}:{}", host, port);

		Router router = Router.router(vertx());
		router.route().handler(BodyHandler.create().setBodyLimit(1024 * 1024));

		setupSseEndpoint(router);
		setupMessageEndpoint(router);
		setupWebSocketHandler(router);

		HttpServerOptions httpOptions = new HttpServerOptions()
			.setHost(host)
			.setPort(port);

		this.server = vertx().createHttpServer(httpOptions);
		server.requestHandler(router);
		server.listen().toCompletionStage().toCompletableFuture().get();
		log.info("MCP server listening on {}:{}", host, server.actualPort());
		return server;
	}

	// ----------------------------------------------------------------
	// Transport 1: HTTP + SSE (Streamable HTTP per MCP 2025-03-26)
	// ----------------------------------------------------------------

	/**
	 * SSE endpoint: client opens a GET connection and receives a stream of events.
	 * The first event tells the client the endpoint URL to POST messages to.
	 */
	private void setupSseEndpoint(Router router) {
		router.get(MCPConstants.SSE_PATH).handler(rc -> {
			// Apply CORS headers based on auth configuration
			mcpAuthHandler.applyCorsHeaders(rc);

			// Authenticate if enabled
			if (mcpAuthHandler.isEnabled()) {
				mcpAuthHandler.authenticateHttp(rc, "sse")
					.onSuccess(user -> {
						// Store user in routing context for later use
						rc.put("mcpUser", user);
						handleSseConnection(rc);
					})
					.onFailure(err -> {
						rc.response().setStatusCode(401).end("Unauthorized: " + err.getMessage());
					});
			} else {
				handleSseConnection(rc);
			}
		});
	}

	private void handleSseConnection(io.vertx.ext.web.RoutingContext rc) {
		String sessionId = UUID.randomUUID().toString();
		HttpServerResponse response = rc.response();
		response.setChunked(true);
		response.putHeader("Content-Type", "text/event-stream");
		response.putHeader("Cache-Control", "no-cache");
		response.putHeader("Connection", "keep-alive");

		sseSessions.put(sessionId, response);
		log.info("SSE session opened: {}", sessionId);

		// Send the endpoint event so the client knows where to POST messages
		String endpointUrl = MCPConstants.MESSAGE_PATH + "?sessionId=" + sessionId;
		sendSseEvent(response, "endpoint", endpointUrl);

		response.closeHandler(v -> {
			sseSessions.remove(sessionId);
			log.info("SSE session closed: {}", sessionId);
		});
	}

	/**
	 * Message endpoint: client POSTs JSON-RPC requests here.
	 * Response is sent back via both the HTTP response AND the SSE stream.
	 */
	private void setupMessageEndpoint(Router router) {
		router.post(MCPConstants.MESSAGE_PATH).handler(rc -> {
			// Authenticate if enabled
			if (mcpAuthHandler.isEnabled()) {
				mcpAuthHandler.authenticateHttp(rc, "message")
					.onSuccess(user -> {
						rc.put("mcpUser", user);
						handleMessageRequest(rc);
					})
					.onFailure(err -> {
						rc.response().setStatusCode(401).end("Unauthorized: " + err.getMessage());
					});
			} else {
				handleMessageRequest(rc);
			}
		});
	}

	private void handleMessageRequest(io.vertx.ext.web.RoutingContext rc) {
		String sessionId = rc.queryParams().get("sessionId");
		User user = rc.get("mcpUser");
		JsonObject body;
		try {
			body = rc.body().asJsonObject();
		} catch (Exception e) {
			log.warn("Failed to parse MCP message body", e);
			rc.response().setStatusCode(400).end(
				JsonRpcResponse.error(null, MCPConstants.ERR_PARSE_ERROR, "Invalid JSON").toJson().encode());
			return;
		}

		JsonRpcRequest request = JsonRpcRequest.fromJson(body);
		jsonRpcHandler.handle(request, user).onComplete(ar -> {
			if (ar.failed()) {
				JsonRpcResponse errorResp = JsonRpcResponse.error(
					request.getId(), MCPConstants.ERR_INTERNAL, ar.cause().getMessage());
				rc.response().setStatusCode(500).end(errorResp.toJson().encode());
				return;
			}
			JsonRpcResponse response = ar.result();
			if (response == null) {
				// Notification — no response required
				rc.response().setStatusCode(202).end();
				return;
			}

			String jsonStr = response.toJson().encode();

			// Send via HTTP response
			rc.response()
				.putHeader("Content-Type", "application/json")
				.setStatusCode(200)
				.end(jsonStr);

			// Also push to the SSE stream if session is active
			if (sessionId != null) {
				HttpServerResponse sseResponse = sseSessions.get(sessionId);
				if (sseResponse != null) {
					sendSseEvent(sseResponse, MCPConstants.SSE_EVENT_MESSAGE, jsonStr);
				}
			}
		});
	}

	/**
	 * Write a Server-Sent Event to an open SSE response.
	 */
	private void sendSseEvent(HttpServerResponse response, String event, String data) {
		StringBuilder sb = new StringBuilder();
		sb.append("event: ").append(event).append("\n");
		// Split data by newlines for SSE format
		for (String line : data.split("\n")) {
			sb.append("data: ").append(line).append("\n");
		}
		sb.append("\n");
		response.write(sb.toString());
	}

	// ----------------------------------------------------------------
	// Transport 2: WebSocket
	// ----------------------------------------------------------------

	/**
	 * WebSocket endpoint: full-duplex JSON-RPC over a single connection.
	 * Each text frame is a JSON-RPC request; responses are sent back as text frames.
	 */
	private void setupWebSocketHandler(Router router) {
		// Use the raw WebSocket handler on the HTTP server (registered after listen)
		// We handle WS upgrade requests at /mcp/ws
		router.get("/mcp/ws").handler(rc -> {
			rc.request().toWebSocket()
				.onSuccess(ws -> handleWebSocket(ws))
				.onFailure(err -> {
					log.warn("WebSocket upgrade failed", err);
					rc.response().setStatusCode(400).end("WebSocket upgrade failed");
				});
		});
	}

	private void handleWebSocket(ServerWebSocket ws) {
		String connId = UUID.randomUUID().toString();
		log.info("MCP WebSocket connected: {}", connId);

		// Authenticate WebSocket if enabled
		if (mcpAuthHandler.isEnabled()) {
			wsAuthenticator.authenticate(ws, "mcp")
				.onSuccess(user -> {
					handleAuthenticatedWebSocket(ws, connId, user);
				})
				.onFailure(err -> {
					// WebSocketAuthenticator already closes the connection with 4401
					log.warn("MCP WebSocket authentication failed: {}", err.getMessage());
				});
		} else {
			handleAuthenticatedWebSocket(ws, connId, null);
		}
	}

	private void handleAuthenticatedWebSocket(ServerWebSocket ws, String connId, User user) {
		ws.textMessageHandler(text -> {
			JsonObject body;
			try {
				body = new JsonObject(text);
			} catch (Exception e) {
				JsonRpcResponse error = JsonRpcResponse.error(null, MCPConstants.ERR_PARSE_ERROR, "Invalid JSON");
				ws.writeTextMessage(error.toJson().encode());
				return;
			}

			JsonRpcRequest request = JsonRpcRequest.fromJson(body);
			jsonRpcHandler.handle(request, user).onComplete(ar -> {
				if (ar.failed()) {
					JsonRpcResponse error = JsonRpcResponse.error(
						request.getId(), MCPConstants.ERR_INTERNAL, ar.cause().getMessage());
					ws.writeTextMessage(error.toJson().encode());
					return;
				}
				JsonRpcResponse response = ar.result();
				if (response != null) {
					ws.writeTextMessage(response.toJson().encode());
				}
			});
		});

		ws.closeHandler(v -> {
			log.info("MCP WebSocket disconnected: {}", connId);
		});

		ws.exceptionHandler(err -> {
			log.error("MCP WebSocket error: {}", connId, err);
		});
	}

	// ----------------------------------------------------------------

	public void stop() {
		if (server != null) {
			log.info("Shutting down MCP server");
			// Close all SSE sessions
			for (HttpServerResponse response : sseSessions.values()) {
				try {
					response.end();
				} catch (Exception e) {
					// ignore
				}
			}
			sseSessions.clear();
			server.close();
			server = null;
		}
	}

	public HttpServer getServer() {
		return server;
	}

	public MCPToolRegistry getToolRegistry() {
		return toolRegistry;
	}

}
