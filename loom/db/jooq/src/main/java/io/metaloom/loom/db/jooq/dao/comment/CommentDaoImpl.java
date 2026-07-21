package io.metaloom.loom.db.jooq.dao.comment;

import static io.metaloom.loom.db.jooq.tables.JooqComment.COMMENT;

import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableRecord;

import io.metaloom.loom.db.jooq.AbstractJooqDao;
import io.metaloom.loom.db.jooq.tables.JooqComment;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.comment.CommentDao;

@Singleton
public class CommentDaoImpl extends AbstractJooqDao<Comment> implements CommentDao {

	@Inject
	public CommentDaoImpl(DSLContext ctx) {
		super(ctx);
	}
	
	@Override
	public String getTypeName() {
		return "Comments";
	}

	@Override
	protected Table<? extends TableRecord<?>> getTable() {
		return JooqComment.COMMENT;
	}

	@Override
	protected Class<? extends Comment> getPojoClass() {
		return CommentImpl.class;
	}

	@Override
	public Comment createComment(UUID userUuid, UUID assetUuid, String title, String text) {
		Comment comment = new CommentImpl();
		comment.setTitle(title);
		comment.setText(text);
		comment.setAssetUuid(assetUuid);
		setCreatorEditor(comment, userUuid);
		return comment;
	}

	@Override
	public Comment createCommentForTask(UUID userUuid, UUID taskUuid, String title, String text) {
		Comment comment = new CommentImpl();
		comment.setTitle(title);
		comment.setText(text);
		comment.setTaskUuid(taskUuid);
		setCreatorEditor(comment, userUuid);
		return comment;
	}

	@Override
	public List<Comment> loadForTask(UUID taskUuid) {
		return ctx().select(getTable()).from(getTable())
			.where(COMMENT.TASK_UUID.eq(taskUuid))
			.fetchInto(getPojoClass());
	}

	@Override
	public List<Comment> loadForAsset(UUID assetUuid) {
		return ctx().select(getTable()).from(getTable())
			.where(COMMENT.ASSET_UUID.eq(assetUuid))
			.fetchInto(getPojoClass());
	}

}
