package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.person.PersonAvatarRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonImageResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;
import io.metaloom.loom.rest.model.storage.StorageBackendListResponse;
import io.metaloom.loom.rest.model.storage.StorageCategoryModel;
import io.metaloom.loom.rest.model.storage.StorageReportResponse;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserResponse;

/**
 * {@code GET /api/v1/storage} and {@code /storage/backends}.
 *
 * <p>
 * The arithmetic itself is covered where it can be exercised cheaply, in {@code StorageStatsServiceTest} (module {@code loom/db/jooq}). What is
 * asserted here is that the route serves it, gates it, and joins the catalogue half to the capacity half - and that an upload made through the API
 * really does move the numbers, which is the one thing a pure DAO test cannot show.
 * </p>
 *
 * <p>
 * Every assertion is a delta. The test database is shared and pre-populated, so an absolute count would pass today and break the next time somebody
 * adds a fixture.
 * </p>
 */
public class StorageEndpointTest extends AbstractEndpointTest {

	private static final String[] EXPECTED_CATEGORIES = {
		"ASSET_BINARY", "ASSET_THUMBNAIL", "EMBEDDING_ATTACHMENT", "FACE_CROP", "PERSON_IMAGE", "PERSON_AVATAR", "USER_AVATAR"
	};

	@Test
	public void testTheReportNamesEveryCategoryEvenAtZero() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			StorageReportResponse report = client.loadStorageReport().sync().body();

			assertThat(report.getTimestamp()).as("a report must say when it was taken").isNotNull();
			assertThat(report.getCategories()).extracting(StorageCategoryModel::getCategory)
				.as("a category that vanishes when empty is indistinguishable from a query that broke")
				.containsExactlyInAnyOrder(EXPECTED_CATEGORIES);

			assertThat(report.getThresholds()).isNotNull();
			assertThat(report.getThresholds().getMinFreeSpaceBytes())
				.as("the caller has to be able to render 'x free of the y required' without a second request")
				.isGreaterThanOrEqualTo(0);
		}
	}

	@Test
	public void testTheDefaultBackendReportsItsCapacity() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			StorageBackendListResponse backends = client.loadStorageBackends().sync().body();

			assertThat(backends.getBackends()).isNotEmpty();
			var local = backends.getBackends().stream().filter(backend -> backend.getPoolUuid() == null).findFirst().orElseThrow();
			assertThat(local.getKind()).isEqualTo("filesystem");
			assertThat(local.getFreeBytes()).as("a local directory can always say how much room is left").isNotNull();
			assertThat(local.getTotalBytes()).as("and how big the volume is, or the UI can only show a bare byte count").isNotNull();
			assertThat(local.getWatermark()).isIn("OK", "WARN", "CRITICAL");
			assertThat(local.getError()).isNull();
		}
	}

	/**
	 * A bucket has no capacity, and the report must say so rather than paint it green.
	 */
	@Test
	public void testAnObjectStoreReportsUnknownRatherThanHealthy() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			client.loadStorageBackends().sync().body().getBackends().stream()
				.filter(backend -> "s3".equals(backend.getKind()))
				.forEach(backend -> {
					assertThat(backend.getFreeBytes()).as("an object store has no free space to report").isNull();
					assertThat(backend.getWatermark())
						.as("UNKNOWN, never OK: a bucket is not known to be healthy, it is unmeasurable")
						.isEqualTo("UNKNOWN");
				});
		}
	}

	@Test
	public void testAnUploadedPictureMovesItsCategory() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "storage-report-person");

			StorageCategoryModel before = categoryOf(client, "PERSON_IMAGE");
			PersonImageResponse image = client.uploadPersonImage(person.getUuid(), imageFile(), "image/jpeg", null).sync().body();
			StorageCategoryModel after = categoryOf(client, "PERSON_IMAGE");

			assertThat(after.getElements()).isEqualTo(before.getElements() + 1);
			assertThat(after.getLogicalBytes()).isEqualTo(before.getLogicalBytes() + image.getSize());
			assertThat(after.getDistinctBytes()).isEqualTo(before.getDistinctBytes() + image.getSize());
		}
	}

	/**
	 * The dedupe claim, end to end: two elements over one stored object cost one object's worth of disk.
	 *
	 * <p>
	 * This is the assertion most likely to rot silently, because a regression that stopped deduplicating would leave every other number in the report
	 * looking entirely reasonable.
	 * </p>
	 */
	@Test
	public void testIdenticalBytesUploadedTwiceCostOneObject() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "storage-dedupe-person");
			File shared = imageFile();

			StorageCategoryModel before = categoryOf(client, "PERSON_IMAGE");
			PersonImageResponse first = client.uploadPersonImage(person.getUuid(), shared, "image/jpeg", null).sync().body();
			client.uploadPersonImage(person.getUuid(), shared, "image/jpeg", null).sync().body();
			StorageCategoryModel after = categoryOf(client, "PERSON_IMAGE");

			assertThat(after.getElements()).as("both pictures are catalogued").isEqualTo(before.getElements() + 2);
			assertThat(after.getLogicalBytes()).as("the catalogue claims both").isEqualTo(before.getLogicalBytes() + 2 * first.getSize());
			assertThat(after.getDistinctObjects()).as("but only one object is on disk").isEqualTo(before.getDistinctObjects() + 1);
			assertThat(after.getDistinctBytes()).as("so only one object's bytes are occupied")
				.isEqualTo(before.getDistinctBytes() + first.getSize());
		}
	}

	/**
	 * Designating an avatar moves a row between two categories without storing anything new.
	 */
	@Test
	public void testDesignatingAnAvatarMovesTheRow() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "storage-avatar-person");
			PersonImageResponse image = client.uploadPersonImage(person.getUuid(), imageFile(), "image/jpeg", null).sync().body();

			StorageCategoryModel galleryBefore = categoryOf(client, "PERSON_IMAGE");
			StorageCategoryModel avatarBefore = categoryOf(client, "PERSON_AVATAR");

			client.setPersonAvatar(person.getUuid(), new PersonAvatarRequest().setImageUuid(image.getUuid().toString())).sync();

			assertThat(categoryOf(client, "PERSON_IMAGE").getElements()).isEqualTo(galleryBefore.getElements() - 1);
			assertThat(categoryOf(client, "PERSON_AVATAR").getElements()).isEqualTo(avatarBefore.getElements() + 1);
		}
	}

	@Test
	public void testAUserAvatarIsReportedUnderItsOwnCategory() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UserResponse user = client.createUser(new UserCreateRequest().setUsername("storage-avatar-" + UUID.randomUUID())).sync().body();

			StorageCategoryModel before = categoryOf(client, "USER_AVATAR");
			client.uploadUserAvatar(user.getUuid(), imageFile(), "image/jpeg", null).sync();
			StorageCategoryModel after = categoryOf(client, "USER_AVATAR");

			assertThat(after.getElements()).isEqualTo(before.getElements() + 1);
			assertThat(after.getLogicalBytes()).isGreaterThan(before.getLogicalBytes());
		}
	}

	/**
	 * Deleting an attachment removes the record but not the bytes. The report is what makes that visible.
	 */
	@Test
	public void testDeletingAPictureLeavesItsBytesCountedAsOrphaned() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "storage-orphan-person");
			PersonImageResponse image = client.uploadPersonImage(person.getUuid(), imageFile(), "image/jpeg", null).sync().body();

			StorageReportResponse before = client.loadStorageReport().sync().body();
			client.deletePersonImage(person.getUuid(), image.getUuid()).sync();
			StorageReportResponse after = client.loadStorageReport().sync().body();

			assertThat(after.getOrphanObjects()).isEqualTo(before.getOrphanObjects() + 1);
			assertThat(after.getOrphanBytes()).isEqualTo(before.getOrphanBytes() + image.getSize());
		}
	}

	@Test
	public void testTheReportNeedsReadStorage() throws Exception {
		// No permissions at all: the report says how much of the customer's disk their media occupies,
		// which is an operator read rather than something every signed-in user may have.
		try (LoomHttpClient client = loginClientWith("storage-nobody")) {
			expect(403, "Forbidden", client.loadStorageReport());
			expect(403, "Forbidden", client.loadStorageBackends());
		}
	}

	// ---------------------------------------------------------------------------------------------

	private StorageCategoryModel categoryOf(LoomHttpClient client, String category) throws LoomClientException {
		return client.loadStorageReport().sync().body().getCategories().stream()
			.filter(model -> category.equals(model.getCategory()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("The report is missing the " + category + " row"));
	}

	private PersonResponse createPerson(LoomHttpClient client, String prefix) throws LoomClientException {
		return client.createPerson(new PersonCreateRequest().setAlias(prefix + "-" + UUID.randomUUID())).sync().body();
	}

	/**
	 * A distinct image per call, so two uploads that are meant to be different really are - the store is content-addressed, and identical bytes would
	 * collapse into one object and quietly invalidate the delta being asserted.
	 */
	private File imageFile() throws Exception {
		File file = File.createTempFile("storage-report-", ".jpg");
		file.deleteOnExit();
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, (int) (Math.random() * 0xFFFFFF));
		ImageIO.write(image, "jpg", file);
		return file;
	}
}
