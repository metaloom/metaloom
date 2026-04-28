package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.chat.ChatCreateRequest;
import io.metaloom.loom.rest.model.chat.ChatListResponse;
import io.metaloom.loom.rest.model.chat.ChatResponse;
import io.metaloom.loom.rest.model.chat.ChatUpdateRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class ChatEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		ChatResponse chat = client.loadChat(CHAT_UUID).sync().body();
		assertNotNull(chat);
		assertNotNull(chat.getTitle());
		assertNotNull(chat.getMessages());
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		ChatCreateRequest request = new ChatCreateRequest();
		request.setTitle("New test chat");
		request.setMessages(new JsonArray()
			.add(new JsonObject().put("role", "user").put("content", "What assets need review?"))
			.add(new JsonObject().put("role", "assistant").put("content", "There are 3 assets pending review.")
				.put("references", new JsonArray()
					.add(new JsonObject().put("type", "asset").put("id", "a1").put("label", "sunset.jpg")))));

		ChatResponse response = client.createChat(request).sync().body();
		assertNotNull(response);
		assertNotNull(response.getUuid());
		assertEquals("New test chat", response.getTitle());
		assertNotNull(response.getMessages());

		ChatResponse loaded = client.loadChat(response.getUuid()).sync().body();
		assertEquals(response.getUuid(), loaded.getUuid());
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		// Create a chat to delete
		ChatCreateRequest request = new ChatCreateRequest();
		request.setTitle("To delete");
		ChatResponse created = client.createChat(request).sync().body();
		assertNotNull(created.getUuid());

		client.deleteChat(created.getUuid()).sync().body();
		expect(404, "Not Found", client.loadChat(created.getUuid()));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		ChatUpdateRequest update = new ChatUpdateRequest();
		update.setTitle("Updated title");
		update.setMessages(new JsonArray()
			.add(new JsonObject().put("role", "user").put("content", "Updated message")));

		ChatResponse response = client.updateChat(CHAT_UUID, update).sync().body();
		assertEquals("Updated title", response.getTitle());

		ChatResponse loaded = client.loadChat(CHAT_UUID).sync().body();
		assertEquals("Updated title", loaded.getTitle());
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			ChatCreateRequest request = new ChatCreateRequest();
			request.setTitle("chat-" + i);
			client.createChat(request).sync().body();
		}
		ChatListResponse list = client.listChats().sync().body();
		assertNotNull(list);
	}

}
