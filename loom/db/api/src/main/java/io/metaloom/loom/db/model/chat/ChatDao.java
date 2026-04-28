package io.metaloom.loom.db.model.chat;

import java.util.UUID;

import io.metaloom.loom.db.CRUDDao;
import io.metaloom.loom.db.model.user.User;

public interface ChatDao extends CRUDDao<Chat> {

	default Chat createChat(User user, String title) {
		return createChat(user.getUuid(), title);
	}

	Chat createChat(UUID userUuid, String title);

}
