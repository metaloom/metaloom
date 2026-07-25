package io.metaloom.loom.agent.memory.sandbox;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.agent.sandbox.SandboxClient;
import io.metaloom.loom.agent.sandbox.SandboxProvisionListener;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.metaloom.loom.mcp.model.MCPCallerContext;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Renders the caller's memory notes as markdown files and pushes them into a Session Runner.
 *
 * <p>The sandbox session id is the chat uuid, so the owning chat is enough to reconstruct the same caller context the agentic loop resolves — the notes a
 * runner receives are exactly the ones the chat's owner may read, never more.</p>
 *
 * <p>Blocking; called from the orchestrator's worker thread.</p>
 */
@Singleton
public class MemoryMaterializer implements SandboxProvisionListener {

	private static final Logger log = LoggerFactory.getLogger(MemoryMaterializer.class);

	/** Explains the read-only folder in place, so an agent that tries to write learns why it failed. */
	static final String README = """
		# Memory (read-only)

		This folder is a materialized view of your memory bank. It is mounted read-only —
		writing here fails and any change would be discarded on the next sync.

		To change memory use the tools:
		  - put_memory(id, content, scope)  — create or overwrite a note
		  - delete_memory(id, scope)        — remove a note
		""";

	private final MemoryService memory;

	private final DaoCollection daos;

	@Inject
	public MemoryMaterializer(MemoryService memory, DaoCollection daos) {
		this.memory = memory;
		this.daos = daos;
	}

	@Override
	public void onProvisioned(String session, SandboxClient client) {
		if (!memory.isEnabled() || !memory.cfg().isMountEnabled()) {
			return;
		}
		try {
			UUID chatUuid = UUID.fromString(session);
			JsonArray files = renderFiles(chatUuid);
			JsonObject result = client.memorySync(files, true);
			log.debug("memory synced into session={} files={} pruned={}", session, result.getInteger("files"), result.getInteger("pruned"));
		} catch (IllegalArgumentException e) {
			// A non-uuid session key is not a chat; nothing to materialize.
			log.debug("skipping memory sync for non-chat session {}", session);
		} catch (Exception e) {
			// Best effort — the memory tools work over the API regardless of the folder.
			log.warn("Could not materialize memory into session {}", session, e);
		}
	}

	/**
	 * The full file set for a chat: a README plus one markdown file per note, keyed by {@code <scope-dir>/<memory-id>}.
	 */
	JsonArray renderFiles(UUID chatUuid) {
		JsonArray files = new JsonArray().add(file("README.md", README));

		MCPCallerContext ctx = callerContextOf(chatUuid);
		if (ctx == null) {
			return files;
		}
		for (MemoryScopeRef scope : memory.scopes().resolve(ctx)) {
			for (MemoryEntry entry : memory.list(scope, null, memory.cfg().getMaxEntriesPerScope())) {
				files.add(file(scope.directory() + "/" + entry.getMemoryId(), memory.renderFile(entry)));
			}
		}
		return files;
	}

	/**
	 * Rebuild the caller context of a chat from server state — the same inputs the agentic loop uses.
	 */
	private MCPCallerContext callerContextOf(UUID chatUuid) {
		Chat chat = daos.chatDao().load(chatUuid);
		if (chat == null || chat.getCreatorUuid() == null) {
			return null;
		}
		UUID ownerUuid = chat.getCreatorUuid();
		List<Group> groups = daos.groupDao().loadGroupsForUser(ownerUuid);
		return new MCPCallerContext(ownerUuid, memory.authorName(ownerUuid),
			groups.stream().map(Group::getUuid).collect(java.util.stream.Collectors.toSet()),
			chat.getSpaceUuid(), chatUuid);
	}

	private static JsonObject file(String path, String content) {
		return new JsonObject().put("path", path).put("content", content);
	}

}
