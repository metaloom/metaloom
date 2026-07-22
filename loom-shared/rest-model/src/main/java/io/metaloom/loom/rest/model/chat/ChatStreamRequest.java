package io.metaloom.loom.rest.model.chat;

import java.util.List;

import io.metaloom.loom.rest.model.RestRequestModel;

/**
 * Request body for the chat agent stream route ({@code POST /chats/:uuid/stream}).
 */
public class ChatStreamRequest implements RestRequestModel {

	private String message;

	private List<String> skillUuids;

	private Boolean think;

	public String getMessage() {
		return message;
	}

	public ChatStreamRequest setMessage(String message) {
		this.message = message;
		return this;
	}

	public List<String> getSkillUuids() {
		return skillUuids;
	}

	public ChatStreamRequest setSkillUuids(List<String> skillUuids) {
		this.skillUuids = skillUuids;
		return this;
	}

	public Boolean getThink() {
		return think;
	}

	public ChatStreamRequest setThink(Boolean think) {
		this.think = think;
		return this;
	}

}
