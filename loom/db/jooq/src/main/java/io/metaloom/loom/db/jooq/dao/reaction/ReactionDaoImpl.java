package io.metaloom.loom.db.jooq.dao.reaction;

import static io.metaloom.loom.db.jooq.tables.JooqAsset.ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqReaction.REACTION;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SelectConditionStep;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.filter.Filter;
import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.api.sort.SortKey;
import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqReaction;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.model.reaction.ReactionDao;
import io.metaloom.loom.db.page.Page;

@Singleton
public class ReactionDaoImpl extends AbstractJooqDao<Reaction> implements ReactionDao {

	@Inject
	public ReactionDaoImpl(DSLContext ctx) {
		super(ctx);
	}

	@Override
	public String getTypeName() {
		return "Reactions";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqReaction.REACTION;
	}

	@Override
	protected Class<? extends Reaction> getPojoClass() {
		return ReactionImpl.class;
	}

	@Override
	public Reaction createReaction(UUID userUuid, String type) {
		Reaction reaction = new ReactionImpl();
		reaction.setType(type);
		setCreatorEditor(reaction, userUuid);
		return reaction;
	}

	@Override
	public Page<Reaction> loadPageForAsset(AssetId assetId, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		SelectConditionStep<Record> query = null;
		if (assetId.isUUID()) {
			query = ctx()
				.select()
				.from(REACTION)
				.where(REACTION.ASSET_UUID.eq(assetId.uuid()));
		} else {
			query = ctx()
				.select()
				.from(REACTION)
				.join(ASSET)
				.on(ASSET.UUID.eq(REACTION.ASSET_UUID))
				.where(ASSET.SHA512SUM.eq(assetId.hashsum().toString()));
		}

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public Page<Reaction> loadPageForTask(UUID taskUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select()
			.from(REACTION)
			.where(REACTION.TASK_UUID.eq(taskUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public Page<Reaction> loadPageForComment(UUID commentUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select()
			.from(REACTION)
			.where(REACTION.COMMENT_UUID.eq(commentUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public Page<Reaction> loadPageForAnnotation(UUID annotationUuid, UUID fromId, int pageSize, List<Filter> filters, SortKey sortBy,
		SortDirection sortDirection) {
		SelectConditionStep<?> query = ctx()
			.select()
			.from(REACTION)
			.where(REACTION.ANNOTATION_UUID.eq(annotationUuid));

		return loadPage(query, fromId, pageSize, filters, sortBy, sortDirection);
	}

	@Override
	public Reaction loadAnnotationReaction(UUID annotationUuid, UUID reactionUuid) {
		return ctx()
			.select(REACTION)
			.from(REACTION)
			.where(REACTION.UUID.eq(reactionUuid))
			.and(REACTION.ANNOTATION_UUID.eq(annotationUuid))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public Reaction loadAssetReaction(AssetId assetId, UUID reactionUuid) {

		if (assetId.isUUID()) {
			return ctx()
				.select(REACTION)
				.from(REACTION)
				.where(REACTION.UUID.eq(reactionUuid))
				.and(REACTION.ASSET_UUID.eq(assetId.uuid()))
				.fetchOneInto(getPojoClass());
		} else {
			return ctx()
				.select(REACTION)
				.from(REACTION)
				.join(ASSET)
				.on(ASSET.UUID.eq(REACTION.ASSET_UUID))
				.where(ASSET.SHA512SUM.eq(assetId.hashsum().toString()))
				.fetchOneInto(getPojoClass());
		}
	}

	@Override
	public Reaction loadCommentReaction(UUID commentUuid, UUID reactionUuid) {
		return ctx()
			.select(REACTION)
			.from(REACTION)
			.where(REACTION.UUID.eq(reactionUuid))
			.and(REACTION.COMMENT_UUID.eq(commentUuid))
			.fetchOneInto(getPojoClass());
	}

	@Override
	public Reaction loadTaskReaction(UUID taskUuid, UUID reactionUuid) {
		return ctx()
			.select(REACTION)
			.from(REACTION)
			.where(REACTION.UUID.eq(reactionUuid))
			.and(REACTION.TASK_UUID.eq(taskUuid))
			.fetchOneInto(getPojoClass());
	}

}
