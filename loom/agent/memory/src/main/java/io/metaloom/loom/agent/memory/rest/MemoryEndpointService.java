package io.metaloom.loom.agent.memory.rest;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_MEMORY;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_MEMORY;
import static io.metaloom.loom.db.model.perm.Permission.READ_MEMORY;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_MEMORY;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.agent.memory.MemoryException;
import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.db.model.memory.MemoryEntryDao.MemoryScopeStats;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * REST access to the agent memory bank for the Loom UI.
 *
 * <p>Scope authorization is service-layer, like every other per-object rule in Loom: the flat permission gates the feature, and the caller's actual scopes
 * are resolved from their user, groups and — for space scope — a named chat. A scope the caller cannot see answers <b>404, not 403</b>, so a foreign group
 * or space is indistinguishable from one that does not exist.</p>
 *
 * <p>jOOQ access is blocking and therefore runs through {@code executeBlocking}.</p>
 */
@Singleton
public class MemoryEndpointService {

	private static final Logger log = LoggerFactory.getLogger(MemoryEndpointService.class);

	private final Vertx vertx;

	private final DaoCollection daos;

	private final MemoryService memory;

	@Inject
	public MemoryEndpointService(Vertx vertx, DaoCollection daos, MemoryService memory) {
		this.vertx = vertx;
		this.daos = daos;
		this.memory = memory;
	}

	/** {@code GET /memory/scopes} */
	public void listScopes(LoomRoutingContext lrc) {
		blocking(lrc, READ_MEMORY, () -> {
			JsonArray scopes = new JsonArray();
			for (MemoryScopeRef scope : resolveScopes(lrc)) {
				MemoryScopeStats stats = daos.memoryEntryDao().stats(scope.scope(), scope.scopeUuid());
				scopes.add(new JsonObject()
					.put("scope", scope.scope().key())
					.put("uuid", scope.scopeUuid().toString())
					.put("label", scope.label())
					.put("ref", scope.ref())
					.put("count", stats.count())
					.put("bytes", stats.bytes())
					.put("maxEntries", memory.cfg().getMaxEntriesPerScope())
					.put("maxBytes", memory.cfg().getMaxScopeBytes())
					.put("writable", !scope.scope().isShared() || memory.cfg().isSharedWriteEnabled()));
			}
			return new JsonObject().put("scopes", scopes);
		});
	}

	/** {@code GET /memory?scope=&ref=&prefix=} */
	public void list(LoomRoutingContext lrc) {
		blocking(lrc, READ_MEMORY, () -> {
			MemoryScopeRef scope = requireScope(lrc);
			String prefix = firstQuery(lrc, "prefix", null);
			int limit = intQuery(lrc, "limit", 200);

			JsonArray entries = new JsonArray();
			for (MemoryEntry entry : memory.list(scope, prefix, limit)) {
				entries.add(summary(entry));
			}
			return new JsonObject().put("scope", scope.ref()).put("entries", entries);
		});
	}

	/** {@code GET /memory/entry?scope=&ref=&id=} */
	public void loadEntry(LoomRoutingContext lrc) {
		blocking(lrc, READ_MEMORY, () -> {
			MemoryScopeRef scope = requireScope(lrc);
			MemoryEntry entry = memory.load(scope, requireId(lrc));
			if (entry == null) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			return summary(entry).put("body", entry.getBody());
		});
	}

	/** {@code POST /memory/entry?scope=&ref=&id=} — 409 when it already exists. */
	public void createEntry(LoomRoutingContext lrc) {
		blocking(lrc, CREATE_MEMORY, () -> {
			MemoryScopeRef scope = requireScope(lrc);
			String id = requireId(lrc);
			if (memory.load(scope, id) != null) {
				throw new LoomRestException(409, LoomRestErrorCode.CONFLICT, "A memory entry with this id already exists.");
			}
			return store(lrc, scope, id);
		});
	}

	/** {@code PUT /memory/entry?scope=&ref=&id=} */
	public void updateEntry(LoomRoutingContext lrc) {
		blocking(lrc, UPDATE_MEMORY, () -> store(lrc, requireScope(lrc), requireId(lrc)));
	}

	/** {@code DELETE /memory/entry?scope=&ref=&id=} */
	public void deleteEntry(LoomRoutingContext lrc) {
		blocking(lrc, DELETE_MEMORY, () -> {
			MemoryScopeRef scope = requireScope(lrc);
			boolean deleted = memory.delete(callerContext(lrc, null), scope, requireId(lrc));
			if (!deleted) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			return new JsonObject().put("deleted", true);
		});
	}

	// -- internals -----------------------------------------------------------

	private JsonObject store(LoomRoutingContext lrc, MemoryScopeRef scope, String id) {
		JsonObject body = lrc.routingContext().body().asJsonObject();
		if (body == null) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "A request body is required.");
		}
		MemoryEntry entry = memory.put(callerContext(lrc, null), scope, id, body.getString("body", ""), body.getString("title"));
		return summary(entry);
	}

	private JsonObject summary(MemoryEntry entry) {
		return new JsonObject()
			.put("uuid", entry.getUuid() == null ? null : entry.getUuid().toString())
			.put("scope", entry.getScope().key())
			.put("id", entry.getMemoryId())
			.put("title", entry.getTitle())
			.put("size", entry.getSize())
			.put("version", entry.getVersion())
			.put("sessionName", entry.getSessionName())
			.put("created", entry.getCreated() == null ? null : entry.getCreated().toString())
			.put("edited", entry.getEdited() == null ? null : entry.getEdited().toString())
			.put("editor", memory.authorName(entry.getEditorUuid()));
	}

	/**
	 * The scopes a REST caller may reach.
	 *
	 * <p>Space scope needs a chat to derive it from, so the caller names one with {@code ?chat=}; ownership of that chat is verified first. Without it the
	 * caller simply sees their user and group scopes.</p>
	 */
	private List<MemoryScopeRef> resolveScopes(LoomRoutingContext lrc) {
		UUID chatUuid = uuidQuery(lrc, "chat");
		return memory.scopes().resolve(callerContext(lrc, chatUuid));
	}

	private MCPCallerContext callerContext(LoomRoutingContext lrc, UUID chatUuid) {
		UUID userUuid = lrc.userUuid();
		Set<UUID> groupUuids = daos.groupDao().loadGroupsForUser(userUuid).stream()
			.map(Group::getUuid)
			.collect(Collectors.toSet());
		UUID spaceUuid = null;
		if (chatUuid != null) {
			var chat = daos.chatDao().load(chatUuid);
			// Foreign chats must be indistinguishable from missing ones.
			if (chat == null || !userUuid.equals(chat.getCreatorUuid())) {
				throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
			}
			spaceUuid = chat.getSpaceUuid();
		}
		return new MCPCallerContext(userUuid, memory.authorName(userUuid), groupUuids, spaceUuid, chatUuid);
	}

	private MemoryScopeRef requireScope(LoomRoutingContext lrc) {
		MemoryScope scope = MemoryScope.parse(firstQuery(lrc, "scope", "user"));
		try {
			return memory.scopes().select(resolveScopes(lrc), scope, firstQuery(lrc, "ref", null));
		} catch (MemoryException e) {
			// An unavailable scope must look exactly like a missing resource.
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
		}
	}

	private String requireId(LoomRoutingContext lrc) {
		String id = firstQuery(lrc, "id", null);
		if (id == null || id.isBlank()) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "An id must be set.");
		}
		return id;
	}

	/**
	 * Run a blocking handler behind a permission check, mapping {@link MemoryException} onto 400 so quota and id errors reach the UI as such.
	 */
	private void blocking(LoomRoutingContext lrc, Permission perm, BlockingHandler handler) {
		lrc.requirePerm(perm).onSuccess(ignore -> vertx.<JsonObject>executeBlocking(handler::handle, false)
			.onSuccess(result -> lrc.sendText(result.encode(), "application/json", 200))
			.onFailure(err -> fail(lrc, err)))
			.onFailure(e -> {
				log.error("Failed to check perms", e);
				lrc.routingContext().fail(new LoomRestException(403, LoomRestErrorCode.MISSING_PERM, "Invalid permissions"));
			});
	}

	private void fail(LoomRoutingContext lrc, Throwable err) {
		if (err instanceof LoomRestException restError) {
			lrc.routingContext().fail(restError);
			return;
		}
		if (err instanceof MemoryException) {
			lrc.routingContext().fail(new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, err.getMessage()));
			return;
		}
		log.warn("Memory request failed", err);
		lrc.routingContext().fail(new LoomRestException(500, LoomRestErrorCode.INTERNAL_ERROR, "The memory request failed."));
	}

	private static String firstQuery(LoomRoutingContext lrc, String key, String def) {
		List<String> values = lrc.queryParam(key);
		return values == null || values.isEmpty() ? def : values.get(0);
	}

	private static int intQuery(LoomRoutingContext lrc, String key, int def) {
		String value = firstQuery(lrc, key, null);
		try {
			return value == null ? def : Integer.parseInt(value);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	private static UUID uuidQuery(LoomRoutingContext lrc, String key) {
		String value = firstQuery(lrc, key, null);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(value);
		} catch (IllegalArgumentException e) {
			throw new LoomRestException(400, LoomRestErrorCode.BAD_REQUEST, "The '" + key + "' parameter must be a uuid.");
		}
	}

	@FunctionalInterface
	private interface BlockingHandler {
		JsonObject handle();
	}

}
