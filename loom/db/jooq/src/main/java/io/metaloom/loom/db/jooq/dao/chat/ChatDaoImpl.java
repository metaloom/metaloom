package io.metaloom.loom.db.jooq.dao.chat;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqChat;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chat.ChatDao;

@Singleton
public class ChatDaoImpl extends AbstractJooqDao<Chat> implements ChatDao {

	@Inject
	public ChatDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Chats";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqChat.CHAT;
	}

	@Override
	protected Class<? extends Chat> getPojoClass() {
		return ChatImpl.class;
	}

	@Override
	public Chat createChat(UUID userUuid, String title) {
		Chat chat = new ChatImpl();
		chat.setTitle(title);
		setCreatorEditor(chat, userUuid);
		return chat;
	}

}
