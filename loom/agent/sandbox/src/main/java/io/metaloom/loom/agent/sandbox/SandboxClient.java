package io.metaloom.loom.agent.sandbox;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import io.metaloom.loom.agent.sandbox.error.SandboxException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Typed HTTP client for one Session Runner's {@code runnerd}.
 *
 * <p>The Loom process calls these instead of ever executing code locally. All calls are blocking and
 * must run on a worker thread (the agentic loop already does). {@code exec} aggregates the runner's
 * streamed JSONL output into a single result — matching the way the loop reports tool results (start
 * then end, no intermediate streaming).</p>
 */
public class SandboxClient {

	/** How much of the exec output tail to keep/report. */
	public static final int OUTPUT_TAIL_CHARS = 8000;

	private final String url;
	private final String token;
	private final HttpClient http;

	public SandboxClient(String url, String token) {
		if (url == null || url.isBlank()) {
			throw new SandboxException("SandboxClient url is not configured");
		}
		this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
		this.token = token == null ? "" : token;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	public boolean healthz() {
		try {
			HttpResponse<String> res = http.send(request("/healthz").timeout(Duration.ofSeconds(5)).GET().build(), BodyHandlers.ofString());
			return res.statusCode() == 200;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	/**
	 * Run a shell command and return the aggregated result:
	 * {@code {exitCode, truncated, timedOut, fullOutputPath, output}}.
	 */
	public JsonObject exec(String command, int timeoutSeconds) {
		JsonObject body = new JsonObject().put("command", command).put("timeout", timeoutSeconds);
		HttpRequest req = request("/exec")
			.timeout(Duration.ofSeconds(timeoutSeconds + 30L))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(body.encode())).build();
		StringBuilder tail = new StringBuilder();
		JsonObject finalLine = null;
		try {
			HttpResponse<Stream<String>> res = http.send(req, BodyHandlers.ofLines());
			if (res.statusCode() >= 400) {
				throw new SandboxException("sandbox /exec -> " + res.statusCode());
			}
			var it = res.body().iterator();
			while (it.hasNext()) {
				String line = it.next().strip();
				if (line.isEmpty()) {
					continue;
				}
				JsonObject item;
				try {
					item = new JsonObject(line);
				} catch (RuntimeException ignore) {
					continue;
				}
				if (item.containsKey("exitCode")) {
					finalLine = item;
					break;
				}
				tail.append(item.getString("data", ""));
				if (tail.length() > OUTPUT_TAIL_CHARS) {
					tail.delete(0, tail.length() - OUTPUT_TAIL_CHARS);
				}
			}
		} catch (IOException e) {
			throw new SandboxException("sandbox /exec failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SandboxException("sandbox /exec interrupted", e);
		}
		JsonObject f = finalLine != null ? finalLine : new JsonObject();
		return new JsonObject()
			.put("exitCode", f.getInteger("exitCode", -1))
			.put("truncated", f.getBoolean("truncated", false))
			.put("timedOut", f.getBoolean("timedOut", false))
			.put("fullOutputPath", f.getValue("fullOutputPath"))
			.put("output", tail.toString());
	}

	public JsonObject writeFile(String path, String content) {
		return postJson("/write_file", new JsonObject().put("path", path).put("content", content));
	}

	public JsonObject readFile(String path, int offset, Integer limit) {
		JsonObject body = new JsonObject().put("path", path).put("offset", offset);
		if (limit != null) {
			body.put("limit", limit);
		}
		return postJson("/read_file", body);
	}

	public JsonObject listFiles(String path) {
		return postJson("/list_files", new JsonObject().put("path", path == null || path.isBlank() ? "." : path));
	}

	/**
	 * Replace the contents of the runner's memory stage with the given files.
	 *
	 * <p>Full-tree and idempotent: {@code prune} removes anything not in the posted set, so a deleted note disappears from the runner too. The daemon
	 * writes through its read-write stage mount; the agent only ever sees the read-only view of the same data.</p>
	 *
	 * @param files
	 *            objects of {@code {"path": "user/notes.md", "content": "…"}}
	 * @param prune
	 *            whether to delete stage files which are not in {@code files}
	 */
	public JsonObject memorySync(JsonArray files, boolean prune) {
		return postJson("/memory_sync", new JsonObject().put("files", files).put("prune", prune));
	}

	/**
	 * Open a raw byte stream of a workspace file (used by the Phase-2 download/preview proxy). The
	 * caller owns the returned stream and must close it.
	 */
	public InputStream downloadStream(String path) {
		HttpRequest req = request("/download?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8))
			.timeout(Duration.ofMinutes(5)).GET().build();
		try {
			HttpResponse<InputStream> res = http.send(req, BodyHandlers.ofInputStream());
			if (res.statusCode() >= 400) {
				res.body().close();
				throw new SandboxException("sandbox /download -> " + res.statusCode());
			}
			return res.body();
		} catch (IOException e) {
			throw new SandboxException("sandbox /download failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SandboxException("sandbox /download interrupted", e);
		}
	}

	private JsonObject postJson(String route, JsonObject body) {
		HttpRequest req = request(route)
			.timeout(Duration.ofSeconds(60))
			.header("Content-Type", "application/json")
			.POST(BodyPublishers.ofString(body.encode())).build();
		try {
			HttpResponse<String> res = http.send(req, BodyHandlers.ofString());
			if (res.statusCode() >= 400) {
				throw new SandboxException("sandbox " + route + " -> " + res.statusCode() + ": " + trim(res.body()));
			}
			return new JsonObject(res.body());
		} catch (IOException e) {
			throw new SandboxException("sandbox " + route + " failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SandboxException("sandbox " + route + " interrupted", e);
		}
	}

	private HttpRequest.Builder request(String route) {
		HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(url + route));
		if (!token.isBlank()) {
			b.header("Authorization", "Bearer " + token);
		}
		return b;
	}

	private static String trim(String s) {
		if (s == null) {
			return "";
		}
		return s.length() <= 200 ? s : s.substring(0, 200);
	}
}
