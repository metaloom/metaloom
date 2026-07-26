package io.metaloom.loom.agent.chat.rest;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_CHAT_SESSION;
import static io.metaloom.loom.db.model.perm.Permission.DELETE_CHAT_SESSION;
import static io.metaloom.loom.db.model.perm.Permission.READ_CHAT_SESSION;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_CHAT_SESSION;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.metaloom.loom.api.error.LoomRestErrorCode;
import io.metaloom.loom.api.error.LoomRestException;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.chatsession.ChatSession;
import io.metaloom.loom.db.model.chatsession.ChatSessionContextRef;
import io.metaloom.loom.db.model.chatsession.ChatSessionDao;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.LoomRoutingContext;
import io.metaloom.loom.rest.builder.LoomModelBuilder;
import io.metaloom.loom.rest.model.chatsession.ChatSessionContextRefModel;
import io.metaloom.loom.rest.model.chatsession.ChatSessionContextRequest;
import io.metaloom.loom.rest.model.chatsession.ChatSessionContextResponse;
import io.metaloom.loom.rest.model.chatsession.ChatSessionCreateRequest;
import io.metaloom.loom.rest.model.chatsession.ChatSessionResponse;
import io.metaloom.loom.rest.model.chatsession.ChatSessionUpdateRequest;
import io.metaloom.loom.rest.parameter.PagingParameters;
import io.metaloom.loom.rest.service.AbstractCRUDEndpointService;
import io.metaloom.loom.rest.validation.LoomModelValidator;

/**
 * CRUD + publish + context-composition for chat sessions. Sessions are user-owned; global permissions
 * gate the feature, per-object ownership is enforced here (a session is viewable by its owner or when
 * published). Context references are live and editable — there is no copy/install.
 */
@Singleton
public class ChatSessionEndpointService extends AbstractCRUDEndpointService<ChatSessionDao, ChatSession> {

	@Inject
	public ChatSessionEndpointService(ChatSessionDao dao, DaoCollection daos, LoomModelBuilder modelBuilder, LoomModelValidator validator) {
		super(dao, daos, modelBuilder, validator);
	}

	@Override
	public void delete(LoomRoutingContext lrc, UUID uuid) {
		// FK ON DELETE CASCADE removes only this session's own skill pins + context refs — never the
		// referenced sessions or their skills.
		delete(lrc, DELETE_CHAT_SESSION, () -> loadOwned(lrc, uuid));
	}

	@Override
	public void list(LoomRoutingContext lrc) {
		checkPerm(lrc, READ_CHAT_SESSION, () -> {
			PagingParameters paging = lrc.pagingParams();
			List<String> scope = lrc.queryParam("scope");
			boolean published = scope != null && scope.contains("published");
			Page<ChatSession> page = published
				? dao().findPublished(paging.from(), paging.limit(), lrc.filterParams().filters(), lrc.sortParams().sortBy(), lrc.sortParams().sortOrder())
				: dao().findByCreator(lrc.userUuid(), paging.from(), paging.limit(), lrc.filterParams().filters(), lrc.sortParams().sortBy(),
					lrc.sortParams().sortOrder());
			lrc.send(modelBuilder.toChatSessionList(page));
		});
	}

	@Override
	public void load(LoomRoutingContext lrc, UUID uuid) {
		load(lrc, READ_CHAT_SESSION, () -> loadViewable(lrc, uuid), session -> enrich(modelBuilder.toResponse(session), session.getUuid()));
	}

	@Override
	public void create(LoomRoutingContext lrc) {
		create(lrc, CREATE_CHAT_SESSION, () -> {
			ChatSessionCreateRequest request = lrc.requestBody(ChatSessionCreateRequest.class);
			validator.validate(request);
			UUID userUuid = lrc.userUuid();
			String name = request.getName() != null && !request.getName().isBlank() ? request.getName() : "Untitled session";
			ChatSession session = dao().createChatSession(userUuid, name, request.getDescription());
			session.setChatUuid(request.getChatUuid());
			if (request.getTags() != null) {
				session.setTags(request.getTags().toArray(new String[0]));
			}
			update(request::getMeta, session::setMeta);
			session.setPublished(request.isPublished());
			return session;
		}, modelBuilder::toResponse);
	}

	@Override
	public void update(LoomRoutingContext lrc, UUID uuid) {
		// The publish flag is managed via the dedicated publish/unpublish routes, so this partial update
		// never touches it (avoiding a primitive-boolean reset).
		update(lrc, UPDATE_CHAT_SESSION, () -> {
			ChatSessionUpdateRequest request = lrc.requestBody(ChatSessionUpdateRequest.class);
			validator.validate(request);
			ChatSession session = requireOwned(lrc, uuid);
			update(request::getName, session::setName);
			update(request::getDescription, session::setDescription);
			update(request::getMeta, session::setMeta);
			if (request.getTags() != null) {
				session.setTags(request.getTags().toArray(new String[0]));
			}
			setEditor(session, lrc.userUuid());
			return session;
		}, modelBuilder::toResponse);
	}

	/** {@code POST /chat-sessions/:uuid/publish} */
	public void publish(LoomRoutingContext lrc, UUID uuid) {
		setPublished(lrc, uuid, true);
	}

	/** {@code POST /chat-sessions/:uuid/unpublish} */
	public void unpublish(LoomRoutingContext lrc, UUID uuid) {
		setPublished(lrc, uuid, false);
	}

	private void setPublished(LoomRoutingContext lrc, UUID uuid, boolean published) {
		checkPerm(lrc, UPDATE_CHAT_SESSION, () -> {
			ChatSession session = requireOwned(lrc, uuid);
			session.setPublished(published);
			setEditor(session, lrc.userUuid());
			dao().update(session);
			lrc.send(enrich(modelBuilder.toResponse(session), uuid));
		});
	}

	/** {@code GET /chat-sessions/:uuid/context} */
	public void getContext(LoomRoutingContext lrc, UUID uuid) {
		checkPerm(lrc, READ_CHAT_SESSION, () -> {
			ChatSession session = loadViewable(lrc, uuid);
			if (session == null) {
				throw notFound();
			}
			lrc.send(contextResponse(uuid));
		});
	}

	/** {@code PUT /chat-sessions/:uuid/context} — replace the whole set of context references. */
	public void putContext(LoomRoutingContext lrc, UUID uuid) {
		checkPerm(lrc, UPDATE_CHAT_SESSION, () -> {
			requireOwned(lrc, uuid);
			ChatSessionContextRequest request = lrc.requestBody(ChatSessionContextRequest.class);
			List<ChatSessionContextRef> refs = new ArrayList<>();
			if (request != null && request.getRefs() != null) {
				for (ChatSessionContextRefModel model : request.getRefs()) {
					refs.add(new ChatSessionContextRef(uuid, model.getSourceSessionUuid(), model.isIncludeChatHistory(), model.isIncludeSkills(),
						model.isIncludeFilesystem(), model.getOrdinal()));
				}
			}
			dao().replaceContextRefs(uuid, refs);
			lrc.send(contextResponse(uuid));
		});
	}

	// -- helpers ------------------------------------------------------------
	private ChatSessionResponse enrich(ChatSessionResponse response, UUID uuid) {
		response.setContextRefs(dao().loadContextRefs(uuid).stream().map(modelBuilder::toContextRefModel).toList());
		response.setSkills(dao().loadSkillPins(uuid).stream().map(modelBuilder::toSkillPinModel).toList());
		return response;
	}

	private ChatSessionContextResponse contextResponse(UUID uuid) {
		return new ChatSessionContextResponse()
			.setRefs(dao().loadContextRefs(uuid).stream().map(modelBuilder::toContextRefModel).toList());
	}

	private ChatSession loadOwned(LoomRoutingContext lrc, UUID uuid) {
		ChatSession session = dao().load(uuid);
		if (session == null || !session.getCreatorUuid().equals(lrc.userUuid())) {
			return null;
		}
		return session;
	}

	private ChatSession requireOwned(LoomRoutingContext lrc, UUID uuid) {
		ChatSession session = loadOwned(lrc, uuid);
		if (session == null) {
			throw notFound();
		}
		return session;
	}

	private ChatSession loadViewable(LoomRoutingContext lrc, UUID uuid) {
		ChatSession session = dao().load(uuid);
		if (session == null) {
			return null;
		}
		if (session.getCreatorUuid().equals(lrc.userUuid()) || session.isPublished()) {
			return session;
		}
		return null;
	}

	private static LoomRestException notFound() {
		return new LoomRestException(404, LoomRestErrorCode.NOT_FOUND, "Element not found.");
	}
}
