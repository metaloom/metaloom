package io.metaloom.cortex.cloud;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A scriptable HTTP server for tests, on a JDK-supplied {@code com.sun.net.httpserver}.
 *
 * <p>This is how the OAuth grants and the two provider clients are tested <em>without network
 * access</em>: every token URL and API base URL is a configurable option, so a test points the whole
 * client at {@code http://127.0.0.1:<port>}. Adding a mock HTTP library for this would be a
 * dependency for something the JDK already ships.</p>
 */
public class StubHttpServer implements AutoCloseable {

	/**
	 * @param status  HTTP status to answer with
	 * @param body    response body
	 * @param headers extra response headers
	 */
	public record Response(int status, String body, Map<String, String> headers) {

		public static Response ok(String body) {
			return new Response(200, body, Map.of());
		}

		public static Response error(int status, String body) {
			return new Response(status, body, Map.of());
		}

		public Response withHeader(String name, String value) {
			Map<String, String> merged = new ConcurrentHashMap<>(headers);
			merged.put(name, value);
			return new Response(status, body, merged);
		}
	}

	/**
	 * @param path  the requested path
	 * @param query the raw query string, or null
	 * @param body  the request body
	 */
	public record Request(String path, String query, String body) {
	}

	private final HttpServer server;
	private final Deque<Response> scripted = new ArrayDeque<>();
	private final List<Request> received = new ArrayList<>();
	private Response fallback = Response.ok("{}");

	public StubHttpServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
	}

	/**
	 * @return the base URL, without a trailing slash
	 */
	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/**
	 * Queue one response. Responses are consumed in order; once the queue is empty the fallback is
	 * served, which keeps a test from having to script requests it does not care about.
	 *
	 * @param response the response to serve next
	 * @return this
	 */
	public StubHttpServer enqueue(Response response) {
		scripted.addLast(response);
		return this;
	}

	public StubHttpServer enqueueJson(String json) {
		return enqueue(Response.ok(json));
	}

	public StubHttpServer fallback(Response response) {
		this.fallback = response;
		return this;
	}

	/**
	 * @return every request received so far, in order
	 */
	public List<Request> received() {
		return List.copyOf(received);
	}

	public Request lastRequest() {
		return received.isEmpty() ? null : received.get(received.size() - 1);
	}

	public int requestCount() {
		return received.size();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		synchronized (this) {
			received.add(new Request(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(), body));
		}
		Response response;
		synchronized (this) {
			response = scripted.isEmpty() ? fallback : scripted.removeFirst();
		}
		byte[] bytes = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
		response.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
