package io.metaloom.loom.rest.model.chatsession;

import java.util.List;

import io.metaloom.loom.rest.model.example.Example;
import io.metaloom.loom.rest.model.example.ExampleValues;
import io.metaloom.loom.rest.model.example.impl.ExampleImpl;
import io.netty.handler.codec.http.HttpResponseStatus;

public interface ChatSessionExamples extends ExampleValues {

	default Example chatSessionUpdateRequestExample() {
		return new ExampleImpl(chatSessionUpdateRequest(), "The chat session update request", HttpResponseStatus.OK);
	}

	default Example chatSessionCreateRequestExample() {
		return new ExampleImpl(chatSessionCreateRequest(), "The chat session create request", HttpResponseStatus.CREATED);
	}

	default Example chatSessionResponseExample() {
		return new ExampleImpl(chatSessionResponse(), "The chat session response", HttpResponseStatus.OK);
	}

	default Example chatSessionListResponseExample() {
		return new ExampleImpl(chatSessionListResponse(), "The chat session list response", HttpResponseStatus.OK);
	}

	default Example chatSessionContextRequestExample() {
		return new ExampleImpl(chatSessionContextRequest(), "The chat session context request", HttpResponseStatus.OK);
	}

	default ChatSessionResponse chatSessionResponse() {
		ChatSessionResponse model = new ChatSessionResponse();
		model.setUuid(uuidC());
		model.setChatUuid(uuidC());
		model.setName("Beach video pipeline");
		model.setDescription("A coding session that builds a small pipeline to tag beach footage.");
		model.setTags(List.of("video", "pipeline"));
		model.setPublished(true);
		model.setHasFilesystem(true);
		model.setFsSize(20480L);
		model.setMeta(meta());
		setCreatorEditor(model);
		return model;
	}

	default ChatSessionListResponse chatSessionListResponse() {
		ChatSessionListResponse model = new ChatSessionListResponse();
		model.setMetainfo(pagingInfo());
		model.add(chatSessionResponse());
		model.add(chatSessionResponse());
		return model;
	}

	default ChatSessionCreateRequest chatSessionCreateRequest() {
		ChatSessionCreateRequest model = new ChatSessionCreateRequest();
		model.setChatUuid(uuidC());
		model.setName("Beach video pipeline");
		model.setDescription("A coding session that builds a small pipeline to tag beach footage.");
		model.setTags(List.of("video", "pipeline"));
		return model;
	}

	default ChatSessionUpdateRequest chatSessionUpdateRequest() {
		ChatSessionUpdateRequest model = new ChatSessionUpdateRequest();
		model.setName("Beach video pipeline (v2)");
		model.setDescription("Updated description.");
		model.setTags(List.of("video", "pipeline", "tagging"));
		model.setPublished(true);
		return model;
	}

	default ChatSessionContextRequest chatSessionContextRequest() {
		ChatSessionContextRequest model = new ChatSessionContextRequest();
		model.setRefs(List.of(new ChatSessionContextRefModel()
			.setSourceSessionUuid(uuidC())
			.setIncludeFilesystem(true)
			.setIncludeSkills(true)
			.setOrdinal(0)));
		return model;
	}

}
