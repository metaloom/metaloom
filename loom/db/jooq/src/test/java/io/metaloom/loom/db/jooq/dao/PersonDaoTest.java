package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqPersonImage.PERSON_IMAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
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
	 * Generate a unique SHA-512 hash based on the index so two gallery assets do not collide on the asset PK.
	 */
	private SHA512 uniqueSha(int i) {
		return SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%04x", i));
	}

	private Asset storeAsset(User user, int i) {
		Asset asset = assetDao().createAsset(user.getUuid(), uniqueSha(i), IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

	private void addGalleryImage(Person person, Asset asset) {
		context.ctx()
			.insertInto(PERSON_IMAGE)
			.set(PERSON_IMAGE.PERSON_UUID, person.getUuid())
			.set(PERSON_IMAGE.ASSET_UUID, asset.getUuid())
			.execute();
	}

	private int galleryImageCount(Person person) {
		return context.ctx().fetchCount(PERSON_IMAGE, PERSON_IMAGE.PERSON_UUID.eq(person.getUuid()));
	}

	/**
	 * Deleting a person cascades its {@code person_image} gallery rows (FK {@code person_uuid ... ON DELETE CASCADE} from
	 * {@code V2.26__add_person.sql}); the referenced assets are a shared resource and must survive.
	 */
	@Test
	public void testDeletingPersonCascadesGalleryImages() {
		User user = adminUser();
		Asset first = storeAsset(user, 1);
		Asset second = storeAsset(user, 2);

		Person person = personDao().createPerson(user, "gallery_person");
		person.setPrimaryImageUuid(first.getUuid());
		personDao().store(person);

		addGalleryImage(person, first);
		addGalleryImage(person, second);
		assertEquals(2, galleryImageCount(person), "Both gallery rows should exist before the delete");

		personDao().delete(person.getUuid());

		assertNull(personDao().load(person.getUuid()), "The person row is gone");
		assertEquals(0, galleryImageCount(person), "The person_image gallery rows must have cascaded with the person");
		assertNotNull(assetDao().load(first.getUuid()), "The primary gallery asset is shared and must survive the person delete");
		assertNotNull(assetDao().load(second.getUuid()), "The secondary gallery asset must survive the person delete");
	}

	/**
	 * Pins the FK action on {@code person.primary_image_uuid}: {@code V2.26__add_person.sql} declares it {@code ON DELETE
	 * SET NULL}, so deleting the referenced asset must null the pointer rather than cascade the person or block the delete.
	 */
	@Test
	public void testDeletingPrimaryImageAssetNullsThePointer() {
		User user = adminUser();
		Asset primary = storeAsset(user, 3);

		Person person = personDao().createPerson(user, "primary_person");
		person.setPrimaryImageUuid(primary.getUuid());
		personDao().store(person);
		assertEquals(primary.getUuid(), personDao().load(person.getUuid()).getPrimaryImageUuid(),
			"The primary image pointer should resolve before the asset is deleted");

		assetDao().delete(primary.getUuid());

		Person reloaded = personDao().load(person.getUuid());
		assertNotNull(reloaded, "The person must survive deletion of its primary image asset (SET NULL, not CASCADE)");
		assertNull(reloaded.getPrimaryImageUuid(), "primary_image_uuid must be SET NULL when the referenced asset is deleted");
	}

}
