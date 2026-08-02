package io.metaloom.loom.test.integration.node;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * A minimal Google Drive v3 server, enough to drive the real client end to end.
 *
 * <p>There is no MinIO equivalent for Drive or Graph, so an integration test cannot boot a real
 * implementation the way {@code S3SourceNodeIntegrationTest} does. This serves the five endpoints the
 * client actually calls, with the field names and envelope shapes Google uses, so everything below
 * the transport is exercised for real: the URL and query construction, the JSON mapping, the token
 * handling, the retry policy, the materializer and the media handle.</p>
 *
 * <p>What it deliberately does not simulate is Google's own behaviour — quota, eventual consistency,
 * shared-drive permissions. Those need a real account, and no test in this repository has one.</p>
 */
public class StubDriveApiServer implements AutoCloseable {

	/** Held content, keyed by file id. */
	private record Entry(String name, String parentId, String mimeType, byte[] content, long version) {
	}

	private final HttpServer server;
	private final Map<String, Entry> files = new LinkedHashMap<>();
	private final AtomicInteger idCounter = new AtomicInteger();

	/** Counted so a test can assert that enumeration moved no bytes. */
	public final AtomicInteger downloadCalls = new AtomicInteger();
	public final AtomicInteger listCalls = new AtomicInteger();

	private long version;

	public StubDriveApiServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.createContext("/token", this::handleToken);
		server.start();
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	public String tokenUrl() {
		return baseUrl() + "/token";
	}

	/**
	 * @return the generated file id
	 */
	public synchronized String putFile(String parentId, String name, byte[] content) {
		String id = "file-" + idCounter.incrementAndGet();
		files.put(id, new Entry(name, parentId, mimeFor(name), content, ++version));
		return id;
	}

	public synchronized void update(String fileId, byte[] content) {
		Entry existing = files.get(fileId);
		files.put(fileId, new Entry(existing.name(), existing.parentId(), existing.mimeType(), content, ++version));
	}

	public synchronized void rename(String fileId, String newName) {
		Entry existing = files.get(fileId);
		files.put(fileId, new Entry(newName, existing.parentId(), existing.mimeType(), existing.content(), ++version));
	}

	public synchronized void remove(String fileId) {
		files.remove(fileId);
		version++;
	}

	@Override
	public void close() {
		server.stop(0);
	}

	// --- request handling ---------------------------------------------------------------

	private void handleToken(HttpExchange exchange) throws IOException {
		respond(exchange, 200, new JsonObject().put("access_token", "stub-token").put("expires_in", 3600).encode());
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

		if (path.equals("/drive/v3/files")) {
			listCalls.incrementAndGet();
			respond(exchange, 200, listResponse(query.get("q")));
			return;
		}
		if (path.equals("/drive/v3/changes/startPageToken")) {
			respond(exchange, 200, new JsonObject().put("startPageToken", String.valueOf(version)).encode());
			return;
		}
		if (path.equals("/drive/v3/changes")) {
			respond(exchange, 200, changesResponse(query.get("pageToken")));
			return;
		}
		if (path.startsWith("/drive/v3/files/")) {
			String fileId = path.substring("/drive/v3/files/".length());
			if (query.containsKey("alt")) {
				downloadCalls.incrementAndGet();
				Entry entry = files.get(fileId);
				if (entry == null) {
					respond(exchange, 404, error("notFound"));
					return;
				}
				respondBytes(exchange, entry.content());
				return;
			}
			Entry entry = files.get(fileId);
			if (entry == null) {
				respond(exchange, 404, error("notFound"));
				return;
			}
			respond(exchange, 200, fileJson(fileId, entry).encode());
			return;
		}
		respond(exchange, 404, error("notFound"));
	}

	private synchronized String listResponse(String query) {
		// The client always scopes by parent; parse the id out of "'<id>' in parents".
		String parentId = null;
		if (query != null) {
			int start = query.indexOf('\'');
			int end = query.indexOf('\'', start + 1);
			if (start >= 0 && end > start) {
				parentId = query.substring(start + 1, end);
			}
		}
		JsonArray entries = new JsonArray();
		for (Map.Entry<String, Entry> file : files.entrySet()) {
			Entry entry = file.getValue();
			boolean matches = parentId == null
				|| parentId.equals("root") ? entry.parentId() == null : parentId.equals(entry.parentId());
			if (matches) {
				entries.add(fileJson(file.getKey(), entry));
			}
		}
		return new JsonObject().put("files", entries).encode();
	}

	private synchronized String changesResponse(String pageToken) {
		long since = pageToken == null || pageToken.isBlank() ? 0 : Long.parseLong(pageToken);
		JsonArray changes = new JsonArray();
		for (Map.Entry<String, Entry> file : files.entrySet()) {
			if (file.getValue().version() > since) {
				changes.add(new JsonObject()
					.put("fileId", file.getKey())
					.put("file", fileJson(file.getKey(), file.getValue())));
			}
		}
		return new JsonObject()
			.put("changes", changes)
			.put("newStartPageToken", String.valueOf(version))
			.encode();
	}

	private static JsonObject fileJson(String id, Entry entry) {
		JsonObject json = new JsonObject()
			.put("id", id)
			.put("name", entry.name())
			.put("mimeType", entry.mimeType())
			.put("size", (long) entry.content().length)
			// Drive reports md5Checksum for any uploaded binary file, and it changes only when the
			// content does. Modelling that matters: without it the client falls back to `version`,
			// which Drive bumps on *every* change including a rename - and a rename would then read
			// as MODIFIED rather than MOVED.
			.put("md5Checksum", md5(entry.content()))
			.put("version", String.valueOf(entry.version()))
			.put("modifiedTime", "2026-07-01T12:00:00.000Z")
			.put("trashed", false);
		if (entry.parentId() != null) {
			json.put("parents", new JsonArray().add(entry.parentId()));
		}
		return json;
	}

	private static String md5(byte[] content) {
		try {
			java.security.MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
			return java.util.HexFormat.of().formatHex(digest.digest(content));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException("MD5 is not available", e);
		}
	}

	private static String error(String reason) {
		return new JsonObject().put("error", new JsonObject()
			.put("code", 404)
			.put("errors", new JsonArray().add(new JsonObject().put("reason", reason)))).encode();
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		Map<String, String> parsed = new LinkedHashMap<>();
		if (rawQuery == null) {
			return parsed;
		}
		for (String pair : rawQuery.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				parsed.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return parsed;
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void respondBytes(HttpExchange exchange, byte[] content) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
		exchange.sendResponseHeaders(200, content.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(content);
		}
	}

	private static String mimeFor(String name) {
		return name.toLowerCase().endsWith(".mp4") ? "video/mp4" : "application/octet-stream";
	}

	/** @return every file id currently held, in insertion order */
	public synchronized List<String> fileIds() {
		return new ArrayList<>(files.keySet());
	}
}
