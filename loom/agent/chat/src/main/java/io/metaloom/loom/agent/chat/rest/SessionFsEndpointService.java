package io.metaloom.loom.agent.chat.rest;

import static io.metaloom.loom.db.model.perm.Permission.READ_CHAT;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.agent.sandbox.SandboxClient;
import io.metaloom.loom.agent.sandbox.SandboxOrchestrator;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;

/**
 * Proxies the browser to a chat's live coding-session filesystem (via the Session Runner's runnerd).
 * All routes require {@code READ_CHAT} and ownership of the chat (matching the chat-stream endpoint),
 * and are keyed by the chat uuid — the same id used as the sandbox session key.
 *
 * <p>Only a <b>live</b> runner can be browsed; if none is running for the chat the routes answer 404.
 * The session-scoped preview ({@code /session/:uuid/...}) is served with a strict
 * {@code Content-Security-Policy: sandbox} so agent-generated pages cannot act as a confused deputy.</p>
 */
@Singleton
public class SessionFsEndpointService {

	private static final Logger log = LoggerFactory.getLogger(SessionFsEndpointService.class);

	/** Hard cap on a single download/preview to bound memory (workspace files are normally small). */
	private static final long MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024;

	private final Vertx vertx;
	private final DaoCollection daos;
	private final SandboxOrchestrator sandbox;

	@Inject
	public SessionFsEndpointService(Vertx vertx, DaoCollection daos, SandboxOrchestrator sandbox) {
		this.vertx = vertx;
		this.daos = daos;
		this.sandbox = sandbox;
	}

	/** {@code GET /session/:uuid/files?path=} — list a workspace directory. */
	public void listFiles(LoomRoutingContext lrc, UUID chatUuid) {
		lrc.requirePerm(READ_CHAT).onSuccess(ignore -> {
			SandboxClient client = requireLiveClient(lrc, chatUuid);
			String path = firstQuery(lrc, "path", ".");
			vertx.<JsonObject>executeBlocking(() -> client.listFiles(path), false)
				.onSuccess(result -> lrc.sendText(result.encode(), "application/json", 200))
				.onFailure(err -> failProxy(lrc, err));
		}).onFailure(e -> failPerm(lrc, e));
	}

	/** {@code GET /session/:uuid/download?path=&inline=} — stream a workspace file to the browser. */
	public void download(LoomRoutingContext lrc, UUID chatUuid) {
		lrc.requirePerm(READ_CHAT).onSuccess(ignore -> {
			SandboxClient client = requireLiveClient(lrc, chatUuid);
			String path = firstQuery(lrc, "path", null);
			if (path == null || path.isBlank()) {
				throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A path must be set.");
			}
			boolean inline = "true".equalsIgnoreCase(firstQuery(lrc, "inline", "false"));
			String fileName = baseName(path);
			String disposition = (inline ? "inline" : "attachment") + "; filename=\"" + fileName.replace("\"", "") + "\"";
			sendFile(lrc, client, path, guessMime(fileName), disposition, false);
		}).onFailure(e -> failPerm(lrc, e));
	}

	/**
	 * {@code GET /session/:uuid/preview?path=} — read-only sandboxed preview of a workspace file.
	 * The path (which may be nested, e.g. {@code report/index.html}) is passed as a query parameter so
	 * arbitrary depth works without server-side wildcard routing.
	 */
	public void preview(LoomRoutingContext lrc, UUID chatUuid) {
		lrc.requirePerm(READ_CHAT).onSuccess(ignore -> {
			SandboxClient client = requireLiveClient(lrc, chatUuid);
			String path = firstQuery(lrc, "path", "index.html");
			sendFile(lrc, client, path, guessMime(baseName(path)), "inline", true);
		}).onFailure(e -> failPerm(lrc, e));
	}

	// -- internals ----------------------------------------------------------
	private void sendFile(LoomRoutingContext lrc, SandboxClient client, String path, String mime, String disposition, boolean sandboxCsp) {
		vertx.<Buffer>executeBlocking(() -> {
			try (InputStream in = client.downloadStream(path)) {
				byte[] bytes = in.readNBytes((int) MAX_DOWNLOAD_BYTES);
				return Buffer.buffer(bytes);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}, false).onSuccess(buffer -> {
			HttpServerResponse response = lrc.routingContext().response();
			response.putHeader("Content-Type", mime);
			response.putHeader("Content-Disposition", disposition);
			response.putHeader("X-Content-Type-Options", "nosniff");
			if (sandboxCsp) {
				// No allow-same-origin: agent-generated preview pages cannot reach the Loom origin.
				response.putHeader("Content-Security-Policy", "sandbox allow-scripts allow-popups allow-forms");
			}
			response.end(buffer);
		}).onFailure(err -> failProxy(lrc, err));
	}

	private SandboxClient requireLiveClient(LoomRoutingContext lrc, UUID chatUuid) {
		loadOwnedChat(lrc, chatUuid);
		SandboxClient client = sandbox.existingClient(chatUuid.toString());
		if (client == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "No active coding session for this chat.");
		}
		return client;
	}

	private Chat loadOwnedChat(LoomRoutingContext lrc, UUID chatUuid) {
		Chat chat = daos.chatDao().load(chatUuid);
		// Foreign chats must be indistinguishable from missing ones.
		if (chat == null || !chat.getCreatorUuid().equals(lrc.userUuid())) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
		}
		return chat;
	}

	private static String firstQuery(LoomRoutingContext lrc, String key, String def) {
		List<String> values = lrc.queryParam(key);
		return values == null || values.isEmpty() ? def : values.get(0);
	}

	private void failProxy(LoomRoutingContext lrc, Throwable err) {
		log.warn("Session filesystem proxy failed", err);
		if (!lrc.routingContext().response().headWritten()) {
			lrc.routingContext().fail(new LoomRestException(502, LoomRestErrorCode.INTERNAL_ERROR, "Coding session filesystem is unavailable."));
		}
	}

	private void failPerm(LoomRoutingContext lrc, Throwable e) {
		log.error("Failed to check perms", e);
		throw new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions");
	}

	private static String baseName(String path) {
		String p = path.replace('\\', '/');
		int idx = p.lastIndexOf('/');
		String name = idx >= 0 ? p.substring(idx + 1) : p;
		return name.isBlank() ? "download" : name;
	}

	private static String guessMime(String name) {
		String lower = name.toLowerCase();
		if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".md")) {
			return "text/plain; charset=utf-8";
		}
		if (lower.endsWith(".json")) {
			return "application/json; charset=utf-8";
		}
		if (lower.endsWith(".csv")) {
			return "text/csv; charset=utf-8";
		}
		if (lower.endsWith(".html") || lower.endsWith(".htm")) {
			return "text/html; charset=utf-8";
		}
		if (lower.endsWith(".png")) {
			return "image/png";
		}
		if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
			return "image/jpeg";
		}
		if (lower.endsWith(".svg")) {
			return "image/svg+xml";
		}
		if (lower.endsWith(".pdf")) {
			return "application/pdf";
		}
		return "application/octet-stream";
	}
}
