package io.metaloom.loom.db.jooq.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.attachment.AttachmentType;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.person.Person;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.storage.StorageCategory;
import io.metaloom.loom.db.storage.StorageCategoryStat;
import io.metaloom.loom.db.storage.StorageReport;
import io.metaloom.utils.hash.SHA512;

/**
 * The storage report's arithmetic.
 *
 * <p>
 * Every assertion here is relative to a delta this test caused. The pooled test database is pre-populated with fixture rows, so an absolute count is
 * a test that passes today and fails the next time somebody adds a fixture.
 * </p>
 */
public class StorageStatsServiceTest extends AbstractJooqTest {

	private static final long IMAGE_SIZE = 4242L;

	/**
	 * Distinct hashes, derived from the fixture one so the length and alphabet stay valid.
	 */
	private SHA512 uniqueSha(String seed) {
		String hex = Integer.toHexString(Math.abs(seed.hashCode()));
		String padded = ("0000000" + hex).substring(hex.length());
		return SHA512.fromString(SHA512SUM.toString().substring(0, 128 - padded.length()) + padded);
	}

	private Attachment store(User user, SHA512 sha, AttachmentType type) {
		Attachment attachment = attachmentDao().createAttachment(user.getUuid(), sha, "picture.jpg", IMAGE_SIZE, IMAGE_MIMETYPE, type);
		attachmentDao().store(attachment);
		return attachment;
	}

	private static StorageCategoryStat statOf(StorageReport report, StorageCategory category) {
		return report.categories().stream()
			.filter(stat -> stat.category() == category)
			.findFirst()
			.orElseThrow(() -> new AssertionError("The report is missing the " + category + " row"));
	}

	@Test
	public void testEveryCategoryIsPresentEvenWhenEmpty() {
		StorageReport report = storageStats().report();
		assertEquals(StorageCategory.values().length, report.categories().size(),
			"Every category must be reported, including the ones at zero - a row that vanishes when empty is indistinguishable from a broken query");
		for (StorageCategory category : StorageCategory.values()) {
			assertNotNull(statOf(report, category));
		}
	}

	@Test
	public void testAnAttachmentIsCountedOnceAgainstItsType() {
		User user = adminUser();
		StorageCategoryStat before = statOf(storageStats().report(), StorageCategory.ASSET_THUMBNAIL);

		store(user, uniqueSha("thumb-a"), AttachmentType.ASSET_THUMBNAIL);

		StorageCategoryStat after = statOf(storageStats().report(), StorageCategory.ASSET_THUMBNAIL);
		assertEquals(before.elements() + 1, after.elements());
		assertEquals(before.logicalBytes() + IMAGE_SIZE, after.logicalBytes());
		assertEquals(before.distinctObjects() + 1, after.distinctObjects());
		assertEquals(before.distinctBytes() + IMAGE_SIZE, after.distinctBytes());
	}

	/**
	 * The dedupe claim, and the one most likely to rot silently: two rows over one stored object cost one object's worth of disk.
	 */
	@Test
	public void testTwoAttachmentsSharingBytesCostOneObject() {
		User user = adminUser();
		SHA512 shared = uniqueSha("shared-bytes");
		StorageCategoryStat before = statOf(storageStats().report(), StorageCategory.ASSET_THUMBNAIL);

		store(user, shared, AttachmentType.ASSET_THUMBNAIL);
		store(user, shared, AttachmentType.ASSET_THUMBNAIL);

		StorageCategoryStat after = statOf(storageStats().report(), StorageCategory.ASSET_THUMBNAIL);
		assertEquals(before.elements() + 2, after.elements(), "Both rows are counted");
		assertEquals(before.logicalBytes() + 2 * IMAGE_SIZE, after.logicalBytes(), "The catalogue claims both");
		assertEquals(before.distinctObjects() + 1, after.distinctObjects(), "But there is only one object on disk");
		assertEquals(before.distinctBytes() + IMAGE_SIZE, after.distinctBytes(), "So only one object's bytes are occupied");
		assertEquals(2 * IMAGE_SIZE - IMAGE_SIZE, after.savedBytes() - before.savedBytes(), "The saving is the difference");
	}

	/**
	 * A person image moves between two categories purely because of where {@code person.avatar_attachment_uuid} points.
	 */
	@Test
	public void testDesignatingAnAvatarMovesTheRowBetweenCategories() {
		User user = adminUser();
		Person person = personDao().createPerson(user, "stats_person");
		personDao().store(person);

		Attachment image = attachmentDao().createAttachment(user.getUuid(), uniqueSha("person-pic"), "pic.jpg", IMAGE_SIZE, IMAGE_MIMETYPE,
			AttachmentType.PERSON_IMAGE);
		image.setPersonUuid(person.getUuid());
		attachmentDao().store(image);

		StorageCategoryStat galleryBefore = statOf(storageStats().report(), StorageCategory.PERSON_IMAGE);
		StorageCategoryStat avatarBefore = statOf(storageStats().report(), StorageCategory.PERSON_AVATAR);

		person.setAvatarAttachmentUuid(image.getUuid());
		personDao().update(person);

		StorageCategoryStat galleryAfter = statOf(storageStats().report(), StorageCategory.PERSON_IMAGE);
		StorageCategoryStat avatarAfter = statOf(storageStats().report(), StorageCategory.PERSON_AVATAR);

		assertEquals(galleryBefore.elements() - 1, galleryAfter.elements(), "The row leaves the gallery bucket");
		assertEquals(avatarBefore.elements() + 1, avatarAfter.elements(), "and lands in the avatar bucket");
	}

	@Test
	public void testAUserAvatarIsReportedUnderItsOwnCategory() {
		User user = userDao().createUser(adminUser().getUuid(), "avatar_stats_user");
		userDao().store(user);

		StorageCategoryStat before = statOf(storageStats().report(), StorageCategory.USER_AVATAR);

		Attachment avatar = attachmentDao().createAttachment(adminUser().getUuid(), uniqueSha("user-pic"), "me.jpg", IMAGE_SIZE, IMAGE_MIMETYPE,
			AttachmentType.USER_AVATAR);
		avatar.setUserUuid(user.getUuid());
		attachmentDao().store(avatar);
		user.setAvatarAttachmentUuid(avatar.getUuid());
		userDao().update(user);

		StorageCategoryStat after = statOf(storageStats().report(), StorageCategory.USER_AVATAR);
		assertEquals(before.elements() + 1, after.elements());
		assertEquals(before.logicalBytes() + IMAGE_SIZE, after.logicalBytes());

		assertEquals(avatar.getUuid(), attachmentDao().loadAvatarByUser(user.getUuid()).getUuid(),
			"The DAO the REST layer uses must find the same row the report counted");
	}

	/**
	 * An attachment_binary nothing points at is what gap G13 leaves behind, and the report has to be able to say so.
	 */
	@Test
	public void testDeletingAnAttachmentLeavesItsBytesOrphaned() {
		User user = adminUser();
		StorageReport before = storageStats().report();

		Attachment attachment = store(user, uniqueSha("soon-orphaned"), AttachmentType.ASSET_THUMBNAIL);
		attachmentDao().delete(attachment.getUuid());

		StorageReport after = storageStats().report();
		assertEquals(before.orphanObjects() + 1, after.orphanObjects(),
			"Deleting an attachment removes the row but not the bytes - that is gap G13, and the report is what makes it visible");
		assertEquals(before.orphanBytes() + IMAGE_SIZE, after.orphanBytes());
	}

	@Test
	public void testPerPoolTotalsCoverTheDefaultStorage() {
		User user = adminUser();
		store(user, uniqueSha("pooled"), AttachmentType.ASSET_THUMBNAIL);

		StorageReport report = storageStats().report();
		assertTrue(report.perPool().stream().anyMatch(stat -> stat.poolUuid() == null && stat.objects() > 0),
			"An attachment stored without a pool belongs to the default local storage, which the report reports as the null pool");
		assertTrue(report.objects() > 0);
		assertTrue(report.distinctBytes() > 0);
	}

	/**
	 * The grand total is its own query, not the sum of the per-category distinct columns.
	 *
	 * <p>
	 * One stored object can be counted under two categories - copying a face crop into a person's gallery shares the bytes deliberately - so summing
	 * the column double-counts. Asserting {@code >=} rather than {@code ==} is the point: it pins the direction of the discrepancy.
	 * </p>
	 */
	@Test
	public void testCategoryDistinctBytesDoNotSumToThePhysicalTotal() {
		StorageReport report = storageStats().report();
		long summed = report.categories().stream()
			.filter(stat -> stat.category() != StorageCategory.ASSET_BINARY)
			.mapToLong(StorageCategoryStat::distinctBytes)
			.sum();
		assertTrue(summed >= report.distinctBytes(),
			"Per-category distinct bytes may double-count a shared object, so their sum can only be at or above the physical total");
	}

	@Test
	public void testAssetBinariesAreReportedAsTheirOwnCategory() {
		StorageCategoryStat assets = statOf(storageStats().report(), StorageCategory.ASSET_BINARY);
		assertTrue(assets.elements() > 0, "The fixture database has assets with locations");
		assertTrue(assets.distinctObjects() <= assets.elements(),
			"An asset imported into two libraries within one pool is one stored object, never more than the row count");
	}

	@Test
	public void testAnUnknownPoolIsNotInvented() {
		StorageReport report = storageStats().report();
		for (StorageReport.StoragePoolStat stat : report.perPool()) {
			UUID poolUuid = stat.poolUuid();
			assertTrue(poolUuid == null || assetPoolDao().load(poolUuid) != null,
				"Every non-null pool in the report must be a pool that exists");
		}
	}
}
