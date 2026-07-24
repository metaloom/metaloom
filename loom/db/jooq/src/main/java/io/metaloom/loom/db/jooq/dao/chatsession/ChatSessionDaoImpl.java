package io.metaloom.loom.db.jooq.dao.chatsession;

import static io.metaloom.loom.db.jooq.tables.JooqChatSession.CHAT_SESSION;
import static io.metaloom.loom.db.jooq.tables.JooqChatSessionContextRef.CHAT_SESSION_CONTEXT_REF;
import static io.metaloom.loom.db.jooq.tables.JooqChatSessionSkill.CHAT_SESSION_SKILL;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.model.chatsession.ChatSession;
import io.metaloom.loom.db.model.chatsession.ChatSessionContextRef;
import io.metaloom.loom.db.model.chatsession.ChatSessionDao;
import io.metaloom.loom.db.model.chatsession.ChatSessionSkillPin;
import io.metaloom.loom.db.page.Page;

@Singleton
public class ChatSessionDaoImpl extends AbstractJooqDao<ChatSession> implements ChatSessionDao {

	@Inject
	public ChatSessionDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "ChatSessions";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return CHAT_SESSION;
	}

	@Override
	protected Class<? extends ChatSession> getPojoClass() {
		return ChatSessionImpl.class;
	}

	@Override
	public ChatSession createChatSession(UUID userUuid, String name, String description) {
		ChatSession session = new ChatSessionImpl();
		session.setName(name);
		session.setDescription(description);
		session.setTags(new String[0]);
		setCreatorEditor(session, userUuid);
		return session;
	}

	@Override
	public Page<ChatSession> findByCreator(UUID userUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		Objects.requireNonNull(userUuid, "The user uuid must be provided");
		SelectConditionStep<?> query = ctx()
			.selectFrom(CHAT_SESSION)
			.where(CHAT_SESSION.CREATOR_UUID.eq(userUuid));
		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public Page<ChatSession> findPublished(UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy, SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.selectFrom(CHAT_SESSION)
			.where(CHAT_SESSION.PUBLISHED.isTrue());
		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public ChatSession loadByChat(UUID chatUuid) {
		Objects.requireNonNull(chatUuid, "The chat uuid must be provided");
		return ctx()
			.selectFrom(CHAT_SESSION)
			.where(CHAT_SESSION.CHAT_UUID.eq(chatUuid))
			.fetchOneInto(ChatSessionImpl.class);
	}

	// -- context references -------------------------------------------------
	@Override
	public List<ChatSessionContextRef> loadContextRefs(UUID sessionUuid) {
		Objects.requireNonNull(sessionUuid, "The session uuid must be provided");
		return ctx()
			.selectFrom(CHAT_SESSION_CONTEXT_REF)
			.where(CHAT_SESSION_CONTEXT_REF.SESSION_UUID.eq(sessionUuid))
			.orderBy(CHAT_SESSION_CONTEXT_REF.ORDINAL.asc())
			.fetch()
			.map(r -> new ChatSessionContextRef(
				r.getSessionUuid(),
				r.getSourceSessionUuid(),
				Boolean.TRUE.equals(r.getIncludeChatHistory()),
				Boolean.TRUE.equals(r.getIncludeSkills()),
				Boolean.TRUE.equals(r.getIncludeFilesystem()),
				r.getOrdinal() == null ? 0 : r.getOrdinal()));
	}

	@Override
	public void replaceContextRefs(UUID sessionUuid, List<ChatSessionContextRef> refs) {
		Objects.requireNonNull(sessionUuid, "The session uuid must be provided");
		ctx().transaction(cfg -> {
			DSLContext tx = cfg.dsl();
			tx.deleteFrom(CHAT_SESSION_CONTEXT_REF)
				.where(CHAT_SESSION_CONTEXT_REF.SESSION_UUID.eq(sessionUuid))
				.execute();
			if (refs != null) {
				for (ChatSessionContextRef ref : refs) {
					tx.insertInto(CHAT_SESSION_CONTEXT_REF)
						.set(CHAT_SESSION_CONTEXT_REF.SESSION_UUID, sessionUuid)
						.set(CHAT_SESSION_CONTEXT_REF.SOURCE_SESSION_UUID, ref.getSourceSessionUuid())
						.set(CHAT_SESSION_CONTEXT_REF.INCLUDE_CHAT_HISTORY, ref.isIncludeChatHistory())
						.set(CHAT_SESSION_CONTEXT_REF.INCLUDE_SKILLS, ref.isIncludeSkills())
						.set(CHAT_SESSION_CONTEXT_REF.INCLUDE_FILESYSTEM, ref.isIncludeFilesystem())
						.set(CHAT_SESSION_CONTEXT_REF.ORDINAL, ref.getOrdinal())
						.execute();
				}
			}
		});
	}

	// -- pinned skill versions ---------------------------------------------
	@Override
	public List<ChatSessionSkillPin> loadSkillPins(UUID sessionUuid) {
		Objects.requireNonNull(sessionUuid, "The session uuid must be provided");
		return ctx()
			.selectFrom(CHAT_SESSION_SKILL)
			.where(CHAT_SESSION_SKILL.SESSION_UUID.eq(sessionUuid))
			.fetch()
			.map(r -> new ChatSessionSkillPin(r.getSessionUuid(), r.getSkillUuid(), r.getSkillVersion()));
	}

	@Override
	public void replaceSkillPins(UUID sessionUuid, List<ChatSessionSkillPin> pins) {
		Objects.requireNonNull(sessionUuid, "The session uuid must be provided");
		ctx().transaction(cfg -> {
			DSLContext tx = cfg.dsl();
			tx.deleteFrom(CHAT_SESSION_SKILL)
				.where(CHAT_SESSION_SKILL.SESSION_UUID.eq(sessionUuid))
				.execute();
			if (pins != null) {
				for (ChatSessionSkillPin pin : pins) {
					tx.insertInto(CHAT_SESSION_SKILL)
						.set(CHAT_SESSION_SKILL.SESSION_UUID, sessionUuid)
						.set(CHAT_SESSION_SKILL.SKILL_UUID, pin.getSkillUuid())
						.set(CHAT_SESSION_SKILL.SKILL_VERSION, pin.getSkillVersion())
						.execute();
				}
			}
		});
	}

}
