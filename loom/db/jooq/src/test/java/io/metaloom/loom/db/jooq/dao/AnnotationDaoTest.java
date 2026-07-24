package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationAsset.ANNOTATION_ASSET;
import static io.metaloom.loom.db.jooq.tables.JooqAnnotationTag.ANNOTATION_TAG;
import static io.metaloom.loom.db.jooq.tables.JooqAnnotationTask.ANNOTATION_TASK;
import static io.metaloom.loom.db.jooq.tables.JooqComment.COMMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.reaction.Reaction;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.task.Task;
import io.metaloom.loom.db.model.user.User;

/**
 * Delete-cascade behaviour of the annotation join and social tables.
 *
 * <p>
 * V2.16 cascades the three annotation join tables ({@code annotation_asset}, {@code annotation_task}, {@code annotation_tag}) from the annotation, and
 * V2.48 added the missing {@code reaction}/{@code comment} -> annotation cascades whose absence made {@code DELETE /annotations} fail with a 500 for
 * any annotation someone had reacted to or commented on. A lost cascade would surface either as an orphan join row or as a foreign-key violation on
 * delete.
 * </p>
 */
public class AnnotationDaoTest extends AbstractJooqTest {

	private Annotation storeAnnotation(User user, Asset asset) {
		Annotation annotation = annotationDao().createAnnotation(user, asset, "annotation", AnnotationType.FEEDBACK);
		annotationDao().store(annotation);
		return annotation;
	}

	private int countAnnotationAsset(UUID annotationUuid) {
		return context.ctx().fetchCount(ANNOTATION_ASSET, ANNOTATION_ASSET.ANNOTATION_UUID.eq(annotationUuid));
	}

	private int countAnnotationTask(UUID annotationUuid) {
		return context.ctx().fetchCount(ANNOTATION_TASK, ANNOTATION_TASK.ANNOTATION_UUID.eq(annotationUuid));
	}

	private int countAnnotationTag(UUID annotationUuid) {
		return context.ctx().fetchCount(ANNOTATION_TAG, ANNOTATION_TAG.ANNOTATION_UUID.eq(annotationUuid));
	}

	/**
	 * Deleting an annotation cascades all three join tables; the linked asset, task and tag survive.
	 */
	@Test
	public void testDeleteCascadesJoinTables() {
		User user = dummyUser();
		Asset asset = asset();

		Annotation annotation = storeAnnotation(user, asset);

		// annotation_asset has no DAO writer, so the link is inserted directly.
		context.ctx().insertInto(ANNOTATION_ASSET, ANNOTATION_ASSET.ANNOTATION_UUID, ANNOTATION_ASSET.ASSET_UUID)
			.values(annotation.getUuid(), asset.getUuid())
			.execute();

		Task task = taskDao().createTask(user, "annotated_task");
		taskDao().store(task);
		taskDao().assignToAnnotation(task.getUuid(), annotation.getUuid());

		Tag tag = tagDao().createTag(user, "annotated_tag", "colors");
		tagDao().store(tag);
		tagDao().tagAnnotation(tag, annotation);

		assertEquals(1, countAnnotationAsset(annotation.getUuid()), "The annotation_asset link should exist before deletion");
		assertEquals(1, countAnnotationTask(annotation.getUuid()), "The annotation_task link should exist before deletion");
		assertEquals(1, countAnnotationTag(annotation.getUuid()), "The annotation_tag link should exist before deletion");

		annotationDao().delete(annotation.getUuid());

		assertNull(annotationDao().load(annotation.getUuid()), "The annotation is gone");
		assertEquals(0, countAnnotationAsset(annotation.getUuid()), "The annotation_asset link must have cascaded");
		assertEquals(0, countAnnotationTask(annotation.getUuid()), "The annotation_task link must have cascaded");
		assertEquals(0, countAnnotationTag(annotation.getUuid()), "The annotation_tag link must have cascaded");

		assertNotNull(assetDao().load(asset.getUuid()), "The asset must survive deletion of the annotation");
		assertNotNull(taskDao().load(task.getUuid()), "The task must survive deletion of the annotation");
		assertNotNull(tagDao().load(tag.getUuid()), "The tag must survive deletion of the annotation");
	}

	/**
	 * Deleting an annotation that carries a reaction and a comment cascades both (V2.48). Before that migration this DELETE failed with a foreign-key
	 * violation.
	 */
	@Test
	public void testDeleteCascadesReactionAndComment() {
		User user = dummyUser();
		Annotation annotation = storeAnnotation(user, asset());

		Reaction reaction = reactionDao().createReaction(user, "thumbsup");
		reaction.setAnnotationUuid(annotation.getUuid());
		reactionDao().store(reaction);

		// comment has no annotation-scoped DAO writer, so the link is inserted directly.
		UUID commentUuid = UUID.randomUUID();
		context.ctx().insertInto(COMMENT, COMMENT.UUID, COMMENT.TEXT, COMMENT.ANNOTATION_UUID,
			COMMENT.CREATOR_UUID, COMMENT.EDITOR_UUID)
			.values(commentUuid, "a comment on the annotation", annotation.getUuid(), user.getUuid(), user.getUuid())
			.execute();

		assertNotNull(reactionDao().load(reaction.getUuid()), "The reaction should exist before deletion");
		assertEquals(1, context.ctx().fetchCount(COMMENT, COMMENT.UUID.eq(commentUuid)), "The comment should exist before deletion");

		annotationDao().delete(annotation.getUuid());

		assertNull(annotationDao().load(annotation.getUuid()), "The annotation is gone");
		assertNull(reactionDao().load(reaction.getUuid()), "The reaction must have cascaded with the annotation");
		assertEquals(0, context.ctx().fetchCount(COMMENT, COMMENT.UUID.eq(commentUuid)), "The comment must have cascaded with the annotation");
	}

}
