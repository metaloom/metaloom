package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqAnnotationTag.ANNOTATION_TAG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.annotation.AnnotationType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.annotation.Annotation;
import io.metaloom.loom.db.model.tag.Tag;
import io.metaloom.loom.db.model.tag.TagDao;
import io.metaloom.loom.db.model.user.User;

public class TagDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<TagDao, Tag> {

	@Override
	public TagDao getDao() {
		return tagDao();
	}

	@Override
	public Tag createElement(User user, int i) {
		return getDao().createTag(user, "tag_" + i, "colors");
	}

	@Override
	public void assertCreate(Tag createdElement) {
		assertEquals("tag_0", createdElement.getName());
		assertEquals("colors", createdElement.getCollection());
	}

	@Override
	public void assertUpdate(Tag updatedElement) {
		assertEquals("new_name", updatedElement.getName());
	}

	@Override
	public void updateElement(Tag tag) {
		tag.setName("new_name");
	}

	/**
	 * Deleting a tag that is linked to an annotation cascades the {@code annotation_tag} join row (V2.16); the annotation survives.
	 */
	@Test
	public void testDeleteCascadesAnnotationTagLink() {
		User user = dummyUser();

		Annotation annotation = annotationDao().createAnnotation(user, asset(), "annotation", AnnotationType.FEEDBACK);
		annotationDao().store(annotation);

		Tag tag = getDao().createTag(user, "linked_tag", "colors");
		getDao().store(tag);
		getDao().tagAnnotation(tag, annotation);

		assertEquals(1, context.ctx().fetchCount(ANNOTATION_TAG, ANNOTATION_TAG.TAG_UUID.eq(tag.getUuid())),
			"The annotation_tag link should exist before deletion");

		getDao().delete(tag.getUuid());

		assertNull(getDao().load(tag.getUuid()), "The tag is gone");
		assertEquals(0, context.ctx().fetchCount(ANNOTATION_TAG, ANNOTATION_TAG.TAG_UUID.eq(tag.getUuid())),
			"The annotation_tag link must have cascaded");
		assertNotNull(annotationDao().load(annotation.getUuid()), "The annotation must survive deletion of the tag");
	}

}
