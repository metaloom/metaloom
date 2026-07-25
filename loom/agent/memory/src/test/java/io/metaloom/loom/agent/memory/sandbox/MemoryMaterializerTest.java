package io.metaloom.loom.agent.memory.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.agent.memory.MemoryScopeRef;
import io.metaloom.loom.agent.memory.MemoryScopeResolver;
import io.metaloom.loom.agent.memory.MemoryService;
import io.metaloom.loom.agent.memory.TestMemoryEntry;
import io.metaloom.loom.agent.sandbox.SandboxClient;
import io.metaloom.loom.api.memory.MemoryScope;
import io.metaloom.loom.api.options.MemoryOptions;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.group.GroupDao;
import io.metaloom.loom.db.model.memory.MemoryEntry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class MemoryMaterializerTest {

	private static final UUID CHAT_UUID = UUID.randomUUID();
	private static final UUID USER_UUID = UUID.randomUUID();
	private static final UUID GROUP_UUID = UUID.randomUUID();

	private MemoryService memory;
	private MemoryOptions options;
	private SandboxClient client;
	private MemoryMaterializer materializer;

	@BeforeEach
	public void setup() {
		memory = mock(MemoryService.class);
		options = mock(MemoryOptions.class);
		client = mock(SandboxClient.class);

		when(memory.isEnabled()).thenReturn(true);
		when(memory.cfg()).thenReturn(options);
		when(options.isMountEnabled()).thenReturn(true);
		when(options.getMaxEntriesPerScope()).thenReturn(500);
		when(memory.authorName(any())).thenReturn("jdoe");
		when(memory.renderFile(any())).thenAnswer(i -> "RENDERED " + ((MemoryEntry) i.getArgument(0)).getMemoryId());

		MemoryScopeResolver resolver = mock(MemoryScopeResolver.class);
		when(memory.scopes()).thenReturn(resolver);
		when(resolver.resolve(any())).thenReturn(List.of(
			new MemoryScopeRef(MemoryScope.USER, USER_UUID, "user"),
			new MemoryScopeRef(MemoryScope.GROUP, GROUP_UUID, "Editors Team")));
		when(memory.list(any(), any(), anyInt())).thenReturn(List.of());

		Chat chat = mock(Chat.class);
		when(chat.getCreatorUuid()).thenReturn(USER_UUID);
		when(chat.getSpaceUuid()).thenReturn(null);
		ChatDao chatDao = mock(ChatDao.class);
		when(chatDao.load(CHAT_UUID)).thenReturn(chat);
		GroupDao groupDao = mock(GroupDao.class);
		when(groupDao.loadGroupsForUser(USER_UUID)).thenReturn(List.of());

		DaoCollection daos = mock(DaoCollection.class);
		when(daos.chatDao()).thenReturn(chatDao);
		when(daos.groupDao()).thenReturn(groupDao);

		materializer = new MemoryMaterializer(memory, daos);
	}

	@Test
	public void testRendersOneFilePerNoteUnderItsScopeDirectory() {
		when(memory.list(any(), any(), anyInt())).thenAnswer(i -> {
			MemoryScopeRef scope = i.getArgument(0);
			return scope.scope() == MemoryScope.USER
				? List.of(entry(MemoryScope.USER, "projects/loom-db.md"))
				: List.of(entry(MemoryScope.GROUP, "conventions.md"));
		});

		JsonArray files = materializer.renderFiles(CHAT_UUID);

		assertEquals("user/projects/loom-db.md", pathAt(files, 1));
		// Shared scopes are namespaced by a slug of their label so two groups cannot collide.
		assertEquals("group/editors-team/conventions.md", pathAt(files, 2));
		assertEquals("RENDERED projects/loom-db.md", files.getJsonObject(1).getString("content"));
	}

	@Test
	public void testAlwaysSeedsTheReadmeExplainingTheReadOnlyFolder() {
		JsonArray files = materializer.renderFiles(CHAT_UUID);
		assertEquals("README.md", pathAt(files, 0));
		String readme = files.getJsonObject(0).getString("content");
		assertTrue(readme.contains("read-only"));
		assertTrue(readme.contains("put_memory"));
	}

	@Test
	public void testUnknownChatYieldsOnlyTheReadme() {
		JsonArray files = materializer.renderFiles(UUID.randomUUID());
		assertEquals(1, files.size());
		assertEquals("README.md", pathAt(files, 0));
	}

	@Test
	public void testSyncsWithPruneSoDeletedNotesDisappear() {
		when(client.memorySync(any(), anyBoolean())).thenReturn(new JsonObject().put("files", 1).put("pruned", 0));

		materializer.onProvisioned(CHAT_UUID.toString(), client);

		verify(client).memorySync(any(), eq(true));
	}

	@Test
	public void testDoesNothingWhenTheFolderIsDisabled() {
		when(options.isMountEnabled()).thenReturn(false);
		materializer.onProvisioned(CHAT_UUID.toString(), client);
		verify(client, never()).memorySync(any(), anyBoolean());
	}

	@Test
	public void testDoesNothingWhenMemoryIsDisabled() {
		when(memory.isEnabled()).thenReturn(false);
		materializer.onProvisioned(CHAT_UUID.toString(), client);
		verify(client, never()).memorySync(any(), anyBoolean());
	}

	@Test
	public void testNonChatSessionKeyIsIgnored() {
		materializer.onProvisioned("not-a-uuid", client);
		verify(client, never()).memorySync(any(), anyBoolean());
	}

	@Test
	public void testSyncFailureIsSwallowed() {
		// Provisioning must not fail because memory could not be pushed — the tools still work.
		when(client.memorySync(any(), anyBoolean())).thenThrow(new RuntimeException("runner gone"));
		materializer.onProvisioned(CHAT_UUID.toString(), client);
		assertFalse(Thread.currentThread().isInterrupted());
	}

	private static String pathAt(JsonArray files, int index) {
		JsonObject file = files.getJsonObject(index);
		assertNotNull(file);
		return file.getString("path");
	}

	private MemoryEntry entry(MemoryScope scope, String id) {
		MemoryEntry entry = new TestMemoryEntry();
		entry.setScope(scope).setMemoryId(id).setTitle("Title").setBody("body").setEdited(Instant.now());
		return entry;
	}

}
