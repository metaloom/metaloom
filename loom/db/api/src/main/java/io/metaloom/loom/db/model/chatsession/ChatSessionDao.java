package io.metaloom.loom.db.model.chatsession;

import java.util.List;
import java.util.UUID;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;

public interface ChatSessionDao extends CRUDDao<ChatSession> {

	default ChatSession createChatSession(User user, String name, String description) {
		return createChatSession(user.getUuid(), name, description);
	}

	ChatSession createChatSession(UUID userUuid, String name, String description);

	/**
	 * Load a page of chat sessions owned by the given user.
	 */
	Page<ChatSession> findByCreator(UUID userUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	/**
	 * Load a page of published chat sessions (the shared session library).
	 */
	Page<ChatSession> findPublished(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection);

	/**
	 * Load the chat session that captures the given chat, if any.
	 */
	ChatSession loadByChat(UUID chatUuid);

	// -- context references -------------------------------------------------

	/**
	 * Load the context references of a session (which other sessions feed it, and which parts).
	 */
	List<ChatSessionContextRef> loadContextRefs(UUID sessionUuid);

	/**
	 * Replace the whole set of context references of a session. Deleting a session cascades its own
	 * refs; this method only touches rows owned by {@code sessionUuid}.
	 */
	void replaceContextRefs(UUID sessionUuid, List<ChatSessionContextRef> refs);

	// -- pinned skill versions ---------------------------------------------

	/**
	 * Load the pinned active skill versions of a session.
	 */
	List<ChatSessionSkillPin> loadSkillPins(UUID sessionUuid);

	/**
	 * Replace the whole set of pinned skill versions of a session.
	 */
	void replaceSkillPins(UUID sessionUuid, List<ChatSessionSkillPin> pins);

}
