package io.metaloom.loom.rest.model.chatsession;

import java.util.List;

import io.metaloom.loom.rest.model.RestResponseModel;

/**
 * The current context references of a session.
 */
public class ChatSessionContextResponse implements RestResponseModel<ChatSessionContextResponse> {

	private List<ChatSessionContextRefModel> refs;

	public List<ChatSessionContextRefModel> getRefs() {
		return refs;
	}

	public ChatSessionContextResponse setRefs(List<ChatSessionContextRefModel> refs) {
		this.refs = refs;
		return this;
	}

	@Override
	public ChatSessionContextResponse self() {
		return this;
	}
}
