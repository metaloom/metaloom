package io.metaloom.loom.rest.service.impl;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_CHAT;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_CHAT;
import static io.metaloom.loom.db.model.perm.Permission.READ_CHAT;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_CHAT;

import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.chat.Chat;
import io.metaloom.loom.db.model.chat.ChatDao;
import io.metaloom.loom.db.model.chat.ChatMeta;
import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.chat.ChatCreateRequest;
import io.metaloom.loom.rest.model.chat.ChatModel;
import io.metaloom.loom.rest.model.chat.ChatUpdateRequest;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

@Singleton
public class ChatEndpointService extends AbstractCRUDEndpointService<ChatDao, Chat> {

	@Inject
	public ChatEndpointService(ChatDao chatDao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(chatDao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		delete(lrc, DELETE_CHAT, uuid);
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		list(lrc, READ_CHAT, modelBuilder::toChatList);
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_CHAT, () -> {
			return dao().load(uuid);
		}, modelBuilder::toResponse);
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_CHAT, () -> {
			ChatCreateRequest request = lrc.requestBody(ChatCreateRequest.class);
			validator.validate(request);

			UUID userUuid = lrc.userUuid();
			String title = request.getTitle();
			Chat chat = dao().createChat(userUuid, title);
			update(request, chat);
			return chat;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		update(lrc, UPDATE_CHAT, () -> {
			ChatUpdateRequest request = lrc.requestBody(ChatUpdateRequest.class);
			validator.validate(request);

			Chat chat = dao().load(uuid);
			update(request, chat);
			return chat;
		}, modelBuilder::toResponse);
	}

	private void update(ChatModel<?> model, Chat chat) {
		update(model::getTitle, chat::setTitle);
		update(model::getMessages, chat::setMessages);
		// The agent loop owns part of chat.meta — the rolling conversation summary above all, which it
		// replays into a later run as a system block. A client round-tripping the meta object it was
		// handed by GET is fine and must keep working, so those keys are quietly restored from the row
		// rather than rejected (ChatMeta.SERVER_OWNED_KEYS).
		JsonObject meta = ChatMeta.merge(model.getMeta(), chat.getMeta());
		if (meta != null) {
			chat.setMeta(meta);
		}
		if (model.getSpaceUuid() != null) {
			requireSpace(model.getSpaceUuid());
			chat.setSpaceUuid(model.getSpaceUuid());
		}
	}

	/**
	 * A chat may only point at a space which exists. The space determines which shared memory scope the agent can reach, so an unchecked value here would
	 * be a way to name a scope the caller was never granted.
	 */
	private void requireSpace(UUID spaceUuid) {
		if (daos().spaceDao().load(spaceUuid) == null) {
			throw new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "The referenced space could not be found.");
		}
	}
}
