package io.metaloom.loom.rest.builder;

import java.util.Arrays;
import java.util.List;

import io.metaloom.loom.db.model.chatsession.ChatSession;
import io.metaloom.loom.db.model.chatsession.ChatSessionContextRef;
import io.metaloom.loom.db.model.chatsession.ChatSessionSkillPin;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.chatsession.ChatSessionContextRefModel;
import io.metaloom.loom.rest.model.chatsession.ChatSessionListResponse;
import io.metaloom.loom.rest.model.chatsession.ChatSessionResponse;
import io.metaloom.loom.rest.model.chatsession.ChatSessionSkillPinModel;

public interface ChatSessionModelBuilder extends ModelBuilder, UserModelBuilder {

	default ChatSessionResponse toResponse(ChatSession session) {
		ChatSessionResponse response = new ChatSessionResponse();
		response.setUuid(session.getUuid());
		response.setChatUuid(session.getChatUuid());
		response.setName(session.getName());
		response.setDescription(session.getDescription());
		response.setTags(session.getTags() == null ? List.of() : Arrays.asList(session.getTags()));
		response.setPublished(session.isPublished());
		response.setFsSize(session.getFsSize());
		response.setHasFilesystem(session.getBlobPath() != null && !session.getBlobPath().isBlank());
		response.setMeta(session.getMeta());
		setStatus(session, response);
		return response;
	}

	default ChatSessionListResponse toChatSessionList(Page<ChatSession> page) {
		return setPage(new ChatSessionListResponse(), page, this::toResponse);
	}

	default ChatSessionContextRefModel toContextRefModel(ChatSessionContextRef ref) {
		return new ChatSessionContextRefModel()
			.setSourceSessionUuid(ref.getSourceSessionUuid())
			.setIncludeChatHistory(ref.isIncludeChatHistory())
			.setIncludeSkills(ref.isIncludeSkills())
			.setIncludeFilesystem(ref.isIncludeFilesystem())
			.setOrdinal(ref.getOrdinal());
	}

	default ChatSessionSkillPinModel toSkillPinModel(ChatSessionSkillPin pin) {
		return new ChatSessionSkillPinModel()
			.setSkillUuid(pin.getSkillUuid())
			.setSkillVersion(pin.getSkillVersion());
	}
}
