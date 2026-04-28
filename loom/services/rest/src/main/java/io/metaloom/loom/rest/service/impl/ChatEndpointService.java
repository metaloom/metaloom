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
		update(model::getMeta, chat::setMeta);
	}
}
