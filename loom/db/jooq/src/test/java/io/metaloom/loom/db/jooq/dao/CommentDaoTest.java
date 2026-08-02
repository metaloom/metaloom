package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.annotation.Annotation;
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

	@Test
	public void testAssetScopedComment() {
		CommentDao dao = getDao();
		UUID assetUuid = asset().getUuid();
		User user = dummyUser();

		AtomicReference<UUID> ref = new AtomicReference<>();
		transaction(t -> {
			Comment comment = dao.createComment(user.getUuid(), assetUuid, "asset_title", "asset text");
			assertEquals(assetUuid, comment.getAssetUuid());
			dao.store(comment);
			ref.set(comment.getUuid());
		});

		// The asset reference must round-trip through the database.
		Comment loaded = dao.load(ref.get());
		assertEquals(assetUuid, loaded.getAssetUuid());

		// loadForAsset must return the asset-scoped comment.
		List<Comment> forAsset = dao.loadForAsset(assetUuid);
		assertTrue(forAsset.stream().anyMatch(c -> c.getUuid().equals(ref.get())),
			"loadForAsset should return the comment created for the asset");
	}

	@Test
	public void testAnnotationScopedComment() {
		CommentDao dao = getDao();
		User user = dummyUser();

		AtomicReference<UUID> annotationRef = new AtomicReference<>();
		AtomicReference<UUID> commentRef = new AtomicReference<>();
		transaction(t -> {
			Annotation annotation = annotationDao().createAnnotation(user, asset(), "annotation_title", AnnotationType.FEEDBACK);
			annotationDao().store(annotation);
			annotationRef.set(annotation.getUuid());

			Comment comment = dao.createCommentForAnnotation(user.getUuid(), annotation.getUuid(), "annotation_comment", "annotation text");
			assertEquals(annotation.getUuid(), comment.getAnnotationUuid());
			dao.store(comment);
			commentRef.set(comment.getUuid());
		});

		// The annotation reference must round-trip through the database.
		Comment loaded = dao.load(commentRef.get());
		assertEquals(annotationRef.get(), loaded.getAnnotationUuid());

		// loadForAnnotation must return the annotation-scoped comment.
		List<Comment> forAnnotation = dao.loadForAnnotation(annotationRef.get());
		assertTrue(forAnnotation.stream().anyMatch(c -> c.getUuid().equals(commentRef.get())),
			"loadForAnnotation should return the comment created for the annotation");

		// An annotation-scoped comment must not leak into the asset-scoped listing.
		List<Comment> forAsset = dao.loadForAsset(asset().getUuid());
		assertTrue(forAsset.stream().noneMatch(c -> c.getUuid().equals(commentRef.get())),
			"loadForAsset must not return a comment that is scoped to an annotation");
	}

}
