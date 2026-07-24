package io.metaloom.loom.rest.model.chatsession;

import java.util.List;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Replace the whole set of context references of a session.
 */
public class ChatSessionContextRequest implements RestRequestModel {

	private List<ChatSessionContextRefModel> refs;

	public List<ChatSessionContextRefModel> getRefs() {
		return refs;
	}

	public ChatSessionContextRequest setRefs(List<ChatSessionContextRefModel> refs) {
		this.refs = refs;
		return this;
	}
}
