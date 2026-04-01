package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.comment.Comment;
import io.metaloom.loom.db.model.comment.CommentDao;
import io.metaloom.loom.db.model.user.User;

public class CommentDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<CommentDao, Comment> {

	@Override
	public CommentDao getDao() {
		return commentDao();
	}

	@Override
	public Comment createElement(User user, int i) {
		return getDao().createComment(user, "title_" + i, "dummy text");
	}

	@Override
	public void assertCreate(Comment createdElement) {
		assertEquals("title_0", createdElement.getTitle());
	}

	@Override
	public void assertUpdate(Comment updatedElement) {
		assertEquals("updated_title", updatedElement.getTitle());
	}

	@Override
	public void updateElement(Comment comment) {
		comment.setTitle("updated_title");
	}

}
