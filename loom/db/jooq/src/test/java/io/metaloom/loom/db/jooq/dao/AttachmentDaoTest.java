package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.db.model.user.User;

public class AttachmentDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<AttachmentDao, Attachment> {

	@Override
	public AttachmentDao getDao() {
		return attachmentDao();
	}

	@Override
	public Attachment createElement(User user, int i) {
		return getDao().createAttachment(user.getUuid(), SHA512SUM, DUMMY_IMAGE_FILENAME, 42L, IMAGE_MIMETYPE, AttachmentType.ASSET_THUMBNAIL);
	}

	@Override
	public void assertCreate(Attachment createdElement) {
		assertEquals(SHA512SUM, createdElement.getSha512sum());
		assertEquals(DUMMY_IMAGE_FILENAME, createdElement.getFilename());
		assertEquals(42L, createdElement.getSize());
		assertEquals(AttachmentType.ASSET_THUMBNAIL, createdElement.getType());
		assertEquals(IMAGE_MIMETYPE, createdElement.getMimeType());
	}

	@Override
	public void assertUpdate(Attachment updatedElement) {
		assertEquals("new_name", updatedElement.getFilename());
	}

	@Override
	public void updateElement(Attachment element) {
		element.setFilename("new_name");
	}

}
