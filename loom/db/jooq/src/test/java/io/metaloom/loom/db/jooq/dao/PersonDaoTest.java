package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.person.PersonDao;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

public class PersonDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<PersonDao, Person> {

	@Override
	public Person createElement(User user, int i) {
		return personDao().createPerson(user, "person_" + i);
	}

	@Override
	public void assertCreate(Person createdElement) {
		assertEquals("person_0", createdElement.getAlias());
	}

	@Override
	public PersonDao getDao() {
		return personDao();
	}

	@Override
	public void updateElement(Person element) {
		element.setAlias("UpdatedAlias");
		element.setFirstname("John");
		element.setLastname("Doe");
	}

	@Override
	public void assertUpdate(Person updatedPerson) {
		assertEquals("UpdatedAlias", updatedPerson.getAlias());
		assertEquals("John", updatedPerson.getFirstname());
		assertEquals("Doe", updatedPerson.getLastname());
	}

	/**
	 * Generate a unique SHA-512 hash based on the index so two person images do not collide on the attachment_binary PK.
	 */
	private SHA512 uniqueSha(int i) {
		return SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%04x", i));
	}

	private Attachment addPersonImage(User user, Person person, int i) {
		Attachment image = attachmentDao().createAttachment(user.getUuid(), uniqueSha(i), "hero_" + i + ".jpg", 42L, IMAGE_MIMETYPE,
			AttachmentType.PERSON_IMAGE);
		image.setPersonUuid(person.getUuid());
		attachmentDao().store(image);
		return image;
	}

	/**
	 * Deleting a person cascades its images (FK {@code attachment.person_uuid ... ON DELETE CASCADE}, {@code V2.90}).
	 *
	 * <p>
	 * A person's pictures belong to nobody else - unlike the {@code person_image} gallery this replaced, which pointed at shared assets and could only
	 * cascade the link row.
	 * </p>
	 */
	@Test
	public void testDeletingPersonCascadesItsImages() {
		User user = adminUser();
		Person person = personDao().createPerson(user, "gallery_person");
		personDao().store(person);

		Attachment first = addPersonImage(user, person, 1);
		Attachment second = addPersonImage(user, person, 2);
		person.setAvatarAttachmentUuid(first.getUuid());
		personDao().update(person);

		assertEquals(2, attachmentDao().listByPerson(person.getUuid()).size(), "Both images should exist before the delete");

		personDao().delete(person.getUuid());

		assertNull(personDao().load(person.getUuid()), "The person row is gone");
		assertNull(attachmentDao().load(first.getUuid()), "The avatar image must have cascaded with the person");
		assertNull(attachmentDao().load(second.getUuid()), "The second image must have cascaded with the person");
	}

	/**
	 * Pins the FK action on {@code person.avatar_attachment_uuid}: {@code V2.90} declares it {@code ON DELETE SET NULL}, so deleting the picture a
	 * person happens to be shown by nulls the pointer rather than deleting the person.
	 */
	@Test
	public void testDeletingTheAvatarImageNullsThePointer() {
		User user = adminUser();
		Person person = personDao().createPerson(user, "avatar_person");
		personDao().store(person);

		Attachment avatar = addPersonImage(user, person, 3);
		Attachment other = addPersonImage(user, person, 4);
		person.setAvatarAttachmentUuid(avatar.getUuid());
		personDao().update(person);
		assertEquals(avatar.getUuid(), personDao().load(person.getUuid()).getAvatarAttachmentUuid(),
			"The avatar pointer should resolve before the image is deleted");

		attachmentDao().delete(avatar.getUuid());

		Person reloaded = personDao().load(person.getUuid());
		assertNotNull(reloaded, "The person must survive deletion of its avatar image (SET NULL, not CASCADE)");
		assertNull(reloaded.getAvatarAttachmentUuid(), "avatar_attachment_uuid must be SET NULL when the referenced image is deleted");

		List<Attachment> remaining = attachmentDao().listByPerson(person.getUuid());
		assertEquals(1, remaining.size(), "The person's other image is untouched");
		assertEquals(other.getUuid(), remaining.get(0).getUuid());
	}

	/**
	 * {@code listByPerson} is scoped to one person and to {@code PERSON_IMAGE}, so a person never sees another's pictures and a face crop written
	 * against the same person by some future producer would not silently appear in their gallery.
	 */
	@Test
	public void testListByPersonIsScopedToThePersonAndTheType() {
		User user = adminUser();
		Person person = personDao().createPerson(user, "listing_person");
		personDao().store(person);
		Person other = personDao().createPerson(user, "other_person");
		personDao().store(other);

		Attachment mine = addPersonImage(user, person, 5);
		addPersonImage(user, other, 6);

		Attachment thumbnail = attachmentDao().createAttachment(user.getUuid(), uniqueSha(7), "thumb.jpg", 42L, IMAGE_MIMETYPE,
			AttachmentType.ASSET_THUMBNAIL);
		thumbnail.setPersonUuid(person.getUuid());
		attachmentDao().store(thumbnail);

		List<Attachment> images = attachmentDao().listByPerson(person.getUuid());
		assertEquals(1, images.size(), "Only this person's PERSON_IMAGE rows are listed");
		assertEquals(mine.getUuid(), images.get(0).getUuid());
		assertTrue(images.stream().allMatch(a -> a.getType() == AttachmentType.PERSON_IMAGE));
	}

}
