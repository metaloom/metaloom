package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.attachment.AttachmentDao;
import io.metaloom.loom.db.model.chat.Chat;
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

	// ---- chat files (V2.112 / V2.113) ------------------------------------------------------

	private Attachment chatFile(User user, Chat chat, String filename) {
		Attachment attachment = getDao().createAttachment(user.getUuid(), SHA512SUM, filename, 42L, IMAGE_MIMETYPE,
			AttachmentType.CHAT_FILE);
		attachment.setChatUuid(chat.getUuid());
		getDao().store(attachment);
		return attachment;
	}

	@Test
	public void testListByChatReturnsOnlyThatChatsFiles() {
		User user = dummyUser();
		Chat chat = chatDao().createChat(user.getUuid(), "with_files");
		chatDao().store(chat);
		Chat other = chatDao().createChat(user.getUuid(), "other");
		chatDao().store(other);

		Attachment mine = chatFile(user, chat, "mine.png");
		chatFile(user, other, "theirs.png");

		List<Attachment> listed = getDao().listByChat(chat.getUuid());

		assertEquals(1, listed.size());
		assertEquals(mine.getUuid(), listed.get(0).getUuid());
	}

	@Test
	public void testListByChatIgnoresOtherAttachmentTypes() {
		User user = dummyUser();
		Chat chat = chatDao().createChat(user.getUuid(), "with_files");
		chatDao().store(chat);

		// A thumbnail that somehow carries a chat uuid must not appear: the manifest is built from
		// this list, and a face crop described to the agent as an attached file would be a lie.
		Attachment thumbnail = getDao().createAttachment(user.getUuid(), SHA512SUM, "thumb.png", 1L, IMAGE_MIMETYPE,
			AttachmentType.ASSET_THUMBNAIL);
		thumbnail.setChatUuid(chat.getUuid());
		getDao().store(thumbnail);

		assertTrue(getDao().listByChat(chat.getUuid()).isEmpty());
	}

	@Test
	public void testListByChatToleratesANullChat() {
		assertTrue(getDao().listByChat(null).isEmpty());
	}

	/**
	 * V2.113 gave {@code attachment.chat_uuid} an {@code ON DELETE CASCADE} FK, which is the whole
	 * lifetime argument of the feature: a file attached to a conversation that no longer exists has
	 * no remaining meaning. Anyone who wants it kept promotes it into the library first, and that
	 * asset is a separate row this cascade cannot reach.
	 */
	@Test
	public void testDeletingTheChatDeletesItsFiles() {
		User user = dummyUser();
		Chat chat = chatDao().createChat(user.getUuid(), "doomed");
		chatDao().store(chat);
		Chat survivor = chatDao().createChat(user.getUuid(), "survivor");
		chatDao().store(survivor);

		Attachment doomed = chatFile(user, chat, "doomed.png");
		Attachment kept = chatFile(user, survivor, "kept.png");

		chatDao().delete(chat.getUuid());

		assertNull(getDao().load(doomed.getUuid()), "the chat's file must go with it");
		assertNotNull(getDao().load(kept.getUuid()), "another chat's file must be untouched");
	}

	@Test
	public void testListChatFilesByCreatorSpansChats() {
		User user = dummyUser();
		Chat first = chatDao().createChat(user.getUuid(), "first");
		chatDao().store(first);
		Chat second = chatDao().createChat(user.getUuid(), "second");
		chatDao().store(second);
		chatFile(user, first, "a.png");
		chatFile(user, second, "b.png");

		// Creator-scoped rather than chat-scoped, because an external MCP client has no chat: this is
		// what backs resources/list.
		assertEquals(2, getDao().listChatFilesByCreator(user.getUuid(), 10).size());
	}

	@Test
	public void testListChatFilesByCreatorRespectsTheLimit() {
		User user = dummyUser();
		Chat chat = chatDao().createChat(user.getUuid(), "many");
		chatDao().store(chat);
		chatFile(user, chat, "a.png");
		chatFile(user, chat, "b.png");
		chatFile(user, chat, "c.png");

		assertEquals(2, getDao().listChatFilesByCreator(user.getUuid(), 2).size());
		assertTrue(getDao().listChatFilesByCreator(user.getUuid(), 0).isEmpty());
		assertTrue(getDao().listChatFilesByCreator(null, 10).isEmpty());
	}

	/**
	 * Chat files are private correspondence rather than material derived from catalogued assets, so
	 * they are excluded from the generic {@code /attachments} listing outright — otherwise anyone
	 * holding {@code READ_ATTACHMENT} could enumerate every file every user had ever dropped into a
	 * conversation. Excluded rather than filtered by owner because this is a keyset-paged query.
	 */
	@Test
	public void testChatFilesAreAbsentFromTheGenericPage() {
		User user = dummyUser();
		Chat chat = chatDao().createChat(user.getUuid(), "private");
		chatDao().store(chat);
		Attachment secret = chatFile(user, chat, "secret.png");

		Attachment thumbnail = getDao().createAttachment(user.getUuid(), SHA512SUM, "thumb.png", 1L, IMAGE_MIMETYPE,
			AttachmentType.ASSET_THUMBNAIL);
		getDao().store(thumbnail);

		List<Attachment> page = new ArrayList<>();
		getDao().loadPage(null, 100, List.of(), null, null).forEach(page::add);

		assertFalse(page.stream().anyMatch(a -> a.getUuid().equals(secret.getUuid())), "a chat file must not be listed");
		assertTrue(page.stream().anyMatch(a -> a.getUuid().equals(thumbnail.getUuid())), "other types must still be listed");
	}

}
