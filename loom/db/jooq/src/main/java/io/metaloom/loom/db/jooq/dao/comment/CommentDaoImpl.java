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
	public Comment createComment(UUID userUuid, String title, String text) {
		Comment comment = new CommentImpl();
		comment.setTitle(title);
		comment.setText(text);
		setCreatorEditor(comment, userUuid);
		return comment;
	}

	@Override
	public List<Comment> loadForTask(UUID taskUuid) {
		return ctx().select(getTable()).from(getTable())
			.where(COMMENT.TASK_UUID.eq(taskUuid))
			.fetchInto(getPojoClass());
	}

}
