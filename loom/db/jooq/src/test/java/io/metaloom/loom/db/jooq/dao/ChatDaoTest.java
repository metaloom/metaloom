package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.chatsession.ChatSession;
import io.metaloom.loom.db.model.user.User;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * CRUD tests for {@link ChatDao}. The focus is the {@code chat.messages} JSONB column: it is the only
 * column in the schema mapped through {@code JsonArrayConverter} (everything else JSONB goes through
 * {@code JsonObjectConverter}), and a conversion regression would silently corrupt chat history. The
 * assertions therefore compare the whole transcript by deep equality instead of spot-checking fields.
 */
public class ChatDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<ChatDao, Chat> {

	@Override
	public ChatDao getDao() {
		return chatDao();
	}

	@Override
	public Chat createElement(User user, int i) {
		Chat chat = getDao().createChat(user.getUuid(), "chat_" + i);
		chat.setMessages(transcript());
		return chat;
	}

	@Override
	public void assertCreate(Chat created) {
		assertEquals("chat_0", created.getTitle());
		assertNotNull(created.getMessages());
		assertEquals(transcript(), created.getMessages(),
			"The whole transcript must round-trip through JsonArrayConverter unchanged");
	}

	@Override
	public void updateElement(Chat chat) {
		chat.setTitle("updated_title");
		chat.setMessages(chat.getMessages().copy().add(new JsonObject()
			.put("role", "assistant")
			.put("content", "It is a cat.")));
	}

	@Override
	public void assertUpdate(Chat updated) {
		assertEquals("updated_title", updated.getTitle());

		JsonArray expected = transcript().add(new JsonObject()
			.put("role", "assistant")
			.put("content", "It is a cat."));
		assertEquals(expected, updated.getMessages(), "The appended message must persist alongside the existing ones");
		assertEquals(4, updated.getMessages().size());
	}

	/**
	 * An empty transcript is the documented default of the column ({@code DEFAULT '[]'::jsonb}) and must
	 * not come back as {@code null} — the chat loop appends to whatever it loads.
	 */
	@Test
	public void testEmptyTranscriptRoundtrip() {
		Chat chat = getDao().createChat(dummyUser().getUuid(), "empty");
		getDao().store(chat);

		Chat loaded = getDao().load(chat.getUuid());
		assertNotNull(loaded);
		assertNotNull(loaded.getMessages(), "An empty transcript must not load as null");
		assertEquals(new JsonArray(), loaded.getMessages());
	}

	/**
	 * V2.52 gave {@code chat_session.chat_uuid} an {@code ON DELETE SET NULL} FK, so that a published
	 * session survives the deletion of the chat it was derived from. Deleting a chat must therefore null
	 * out the reference rather than remove the session — and must not touch any other chat or session.
	 */
	@Test
	public void testDeleteChatDetachesSessionButKeepsIt() {
		User user = dummyUser();

		Chat chat = getDao().createChat(user.getUuid(), "deleted_chat");
		getDao().store(chat);
		ChatSession session = chatSessionDao().createChatSession(user, "attached_session", "desc");
		session.setChatUuid(chat.getUuid());
		chatSessionDao().store(session);

		// A second, untouched chat + session pair that must survive the delete intact.
		Chat survivor = getDao().createChat(user.getUuid(), "surviving_chat");
		getDao().store(survivor);
		ChatSession survivorSession = chatSessionDao().createChatSession(user, "surviving_session", "desc");
		survivorSession.setChatUuid(survivor.getUuid());
		chatSessionDao().store(survivorSession);

		getDao().delete(chat.getUuid());

		assertNull(getDao().load(chat.getUuid()), "The chat should be deleted");

		ChatSession detached = chatSessionDao().load(session.getUuid());
		assertNotNull(detached, "Deleting the chat must NOT delete the attached session");
		assertEquals("attached_session", detached.getName());
		assertNull(detached.getChatUuid(), "V2.52 ON DELETE SET NULL must null the chat reference");

		assertNotNull(getDao().load(survivor.getUuid()), "The unrelated chat must survive");
		ChatSession untouched = chatSessionDao().load(survivorSession.getUuid());
		assertNotNull(untouched, "The unrelated session must survive");
		assertEquals(survivor.getUuid(), untouched.getChatUuid(), "The unrelated session must keep its chat reference");
	}

	/**
	 * A transcript that exercises the structures the chat loop actually stores: nested objects, nested
	 * arrays, numbers and booleans — the parts a converter regression would flatten or drop.
	 */
	private static JsonArray transcript() {
		return new JsonArray()
			.add(new JsonObject()
				.put("role", "system")
				.put("content", "You are a helpful assistant."))
			.add(new JsonObject()
				.put("role", "user")
				.put("content", "What is in this image?")
				.put("assets", new JsonArray().add("asset-a").add("asset-b")))
			.add(new JsonObject()
				.put("role", "assistant")
				.put("content", "A cat.")
				.put("meta", new JsonObject()
					.put("model", "claude-opus-5")
					.put("tokens", 42)
					.put("confidence", 0.95)
					.put("streamed", true)));
	}

}
