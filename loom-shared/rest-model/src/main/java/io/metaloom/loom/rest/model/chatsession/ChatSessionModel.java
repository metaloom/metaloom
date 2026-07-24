package io.metaloom.loom.rest.model.chatsession;

import java.util.List;
import java.util.UUID;

import io.metaloom.loom.rest.model.MetaModel;
import io.metaloom.loom.rest.model.RestModel;

/**
 * Shared field interface for the chat-session request/response models.
 */
public interface ChatSessionModel<T extends ChatSessionModel<T>> extends MetaModel<T>, RestModel {

	UUID getChatUuid();

	T setChatUuid(UUID chatUuid);

	String getName();

	T setName(String name);

	String getDescription();

	T setDescription(String description);

	List<String> getTags();

	T setTags(List<String> tags);

	boolean isPublished();

	T setPublished(boolean published);

}
