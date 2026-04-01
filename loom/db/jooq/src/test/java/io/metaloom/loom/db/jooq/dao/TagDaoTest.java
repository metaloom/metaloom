package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
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

}
