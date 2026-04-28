package io.metaloom.loom.rest.model.chat;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public interface ChatExamples extends ExampleValues {

	default Example chatUpdateRequestExample() {
		return new ExampleImpl(chatUpdateRequest(), "The chat update request", HttpResponseStatus.OK);
	}

	default Example chatCreateRequestExample() {
		return new ExampleImpl(chatCreateRequest(), "The chat create request", HttpResponseStatus.CREATED);
	}

	default Example chatResponseExample() {
		return new ExampleImpl(chatResponse(), "The chat response", HttpResponseStatus.OK);
	}

	default Example chatListResponseExample() {
		return new ExampleImpl(chatListResponse(), "The chat list response", HttpResponseStatus.OK);
	}

	default ChatResponse chatResponse() {
		ChatResponse model = new ChatResponse();
		model.setUuid(uuidC());
		model.setTitle("Campaign asset review");
		model.setMessages(exampleMessages());
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default ChatListResponse chatListResponse() {
		ChatListResponse model = new ChatListResponse();
		model.setMetainfo(pagingInfo());
		model.add(chatResponse());
		model.add(chatResponse());
		return model;
	}

	default ChatUpdateRequest chatUpdateRequest() {
		ChatUpdateRequest model = new ChatUpdateRequest();
		model.setTitle("Updated chat title");
		model.setMessages(exampleMessages());
		model.setMeta(meta());
		return model;
	}

	default ChatCreateRequest chatCreateRequest() {
		ChatCreateRequest model = new ChatCreateRequest();
		model.setTitle("New chat session");
		model.setMessages(exampleMessages());
		model.setMeta(meta());
		return model;
	}

	default JsonArray exampleMessages() {
		return new JsonArray()
			.add(new JsonObject()
				.put("role", "user")
				.put("content", "Show me the latest assets in Campaign Alpha"))
			.add(new JsonObject()
				.put("role", "assistant")
				.put("content", "Here are the most recently updated assets in Campaign Alpha.")
				.put("references", new JsonArray()
					.add(new JsonObject().put("type", "asset").put("id", "a1").put("label", "Hero_Campaign_30s_Final.mp4"))));
	}

}
