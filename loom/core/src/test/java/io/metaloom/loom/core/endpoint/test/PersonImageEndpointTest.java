package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.READ_DETECTION;
import static io.metaloom.loom.db.model.perm.Permission.READ_PERSON;
import static io.metaloom.loom.db.model.perm.Permission.UPDATE_PERSON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.person.PersonAvatarRequest;
import io.metaloom.loom.rest.model.person.PersonCreateRequest;
import io.metaloom.loom.rest.model.person.PersonImageImportRequest;
import io.metaloom.loom.rest.model.person.PersonImageListResponse;
import io.metaloom.loom.rest.model.person.PersonImageResponse;
import io.metaloom.loom.rest.model.person.PersonResponse;

/**
 * Covers {@code /persons/:uuid/images} and {@code /persons/:uuid/avatar}.
 *
 * <p>
 * A person owns their pictures: they reference no asset, which is what lets somebody keep a face when the material they were found in is deleted. That
 * is the property {@link #testAnImportedCropSurvivesDeletingItsAsset()} pins, and it is the reason this exists at all - the avatar it replaced pointed
 * at an asset, so for a person discovered in a video it resolved to the whole video file.
 * </p>
 */
public class PersonImageEndpointTest extends AbstractEndpointTest {

	@Test
	public void testUploadedImageIsListedAndDownloadable() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "hero-person");

			PersonImageResponse image = uploadImage(client, person.getUuid());
			assertNotNull(image.getUuid());
			assertEquals("image/jpeg", image.getMimeType());
			assertTrue(image.getSize() > 0, "the stored size is the uploaded size");
			assertFalse(image.isAvatar(), "uploading a picture does not make it the avatar - that is a separate decision");
			assertEquals("/api/v1/persons/" + person.getUuid() + "/images/" + image.getUuid() + "/data", image.getUrl(),
				"the response carries a URL, so no caller has to know how a person image is addressed");

			PersonImageListResponse list = client.listPersonImages(person.getUuid()).sync().body();
			assertEquals(1, list.getData().size());
			assertEquals(image.getUuid(), list.getData().get(0).getUuid());

			try (var response = client.downloadPersonImage(person.getUuid(), image.getUuid()).sync().body()) {
				assertEquals(200, response.code());
				assertTrue(response.getStream().readAllBytes().length > 0, "the image must have bytes");
				assertEquals("image/jpeg", response.getContentType());
			}
		}
	}

	/**
	 * A person's gallery is theirs alone. An image addressed through the wrong person is answered as missing rather than as forbidden - the pairing is
	 * part of the address, and confirming that the uuid exists elsewhere leaks it.
	 */
	@Test
	public void testAnImageIsNotAddressableThroughAnotherPerson() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse owner = createPerson(client, "owner-person");
			PersonResponse stranger = createPerson(client, "stranger-person");
			PersonImageResponse image = uploadImage(client, owner.getUuid());

			assertEquals(0, client.listPersonImages(stranger.getUuid()).sync().body().getData().size(),
				"the stranger has no images of their own");

			try (var response = client.downloadPersonImage(stranger.getUuid(), image.getUuid()).sync().body()) {
				assertEquals(404, response.code(), "an image of another person is missing at this address, not forbidden");
			}
			expect(404, "Not Found", client.deletePersonImage(stranger.getUuid(), image.getUuid()));

			PersonAvatarRequest avatar = new PersonAvatarRequest().setImageUuid(image.getUuid().toString());
			expect(404, "Not Found", client.setPersonAvatar(stranger.getUuid(), avatar));
		}
	}

	/** Setting, reading back and clearing the avatar. This is the replacement for the {@code primaryImageUuid} round trip. */
	@Test
	public void testAvatarRoundTrips() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "avatar-person");
			assertNull(person.getAvatarUrl(), "a new person has no avatar");

			PersonImageResponse image = uploadImage(client, person.getUuid());
			PersonResponse withAvatar = client.setPersonAvatar(person.getUuid(), new PersonAvatarRequest()
				.setImageUuid(image.getUuid().toString())).sync().body();
			assertEquals(image.getUrl(), withAvatar.getAvatarUrl(), "the avatar url addresses the chosen image");

			PersonResponse reloaded = client.loadPerson(person.getUuid()).sync().body();
			assertEquals(image.getUrl(), reloaded.getAvatarUrl(), "and it survives a reload");
			assertTrue(client.listPersonImages(person.getUuid()).sync().body().getData().get(0).isAvatar(),
				"the listing marks which image is the avatar");

			PersonResponse cleared = client.setPersonAvatar(person.getUuid(), new PersonAvatarRequest()).sync().body();
			assertNull(cleared.getAvatarUrl(), "a blank imageUuid clears the avatar");
			assertNull(client.loadPerson(person.getUuid()).sync().body().getAvatarUrl());
		}
	}

	/**
	 * Deleting the picture a person is shown by leaves them without an avatar rather than deleting them: the foreign key is {@code ON DELETE SET NULL}
	 * (V2.90), so the database nulls the pointer and there is no window in which it dangles.
	 */
	@Test
	public void testDeletingTheAvatarImageLeavesThePersonStanding() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "delete-avatar-person");
			PersonImageResponse image = uploadImage(client, person.getUuid());
			client.setPersonAvatar(person.getUuid(), new PersonAvatarRequest().setImageUuid(image.getUuid().toString())).sync().body();

			client.deletePersonImage(person.getUuid(), image.getUuid()).sync().body();

			PersonResponse reloaded = client.loadPerson(person.getUuid()).sync().body();
			assertNotNull(reloaded, "the person must survive losing their picture");
			assertNull(reloaded.getAvatarUrl(), "and simply has no avatar again");
			assertEquals(0, client.listPersonImages(person.getUuid()).sync().body().getData().size());
		}
	}

	/**
	 * The one-click path from "discovered in a video" to a real avatar, and the property that makes it worth doing: the import is a copy into the
	 * person's own keeping, so deleting the asset the face was found in leaves it standing.
	 */
	@Test
	public void testAnImportedCropSurvivesDeletingItsAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			var asset = client.uploadAsset(imageFile(), LIBRARY_UUID, "image/jpeg").sync().body();
			DetectionResponse detection = createFaceDetection(client, asset.getUuid());
			client.uploadFaceCrop(imageFile(), asset.getUuid(), detection.getUuid(), "192", "facedetect").sync().body();

			PersonResponse person = createPerson(client, "video-person");
			PersonImageResponse imported = client.importPersonImage(person.getUuid(), new PersonImageImportRequest()
				.setDetectionUuid(detection.getUuid().toString())).sync().body();
			client.setPersonAvatar(person.getUuid(), new PersonAvatarRequest().setImageUuid(imported.getUuid().toString())).sync().body();

			client.deleteAsset(asset.getUuid()).sync().body();

			PersonResponse reloaded = client.loadPerson(person.getUuid()).sync().body();
			assertNotNull(reloaded, "the person outlives the material they were found in");
			assertEquals(imported.getUrl(), reloaded.getAvatarUrl(), "and so does the picture of their face");
			assertEquals(1, client.listPersonImages(person.getUuid()).sync().body().getData().size());
			try (var response = client.downloadPersonImage(person.getUuid(), imported.getUuid()).sync().body()) {
				assertEquals(200, response.code(), "the bytes are content-addressed and shared, so they are still there");
				assertTrue(response.getStream().readAllBytes().length > 0);
			}
		}
	}

	/** A detection that has no crop stored has nothing to import. */
	@Test
	public void testImportingFromADetectionWithoutACropIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			var asset = client.uploadAsset(imageFile(), LIBRARY_UUID, "image/jpeg").sync().body();
			DetectionResponse detection = createFaceDetection(client, asset.getUuid());
			PersonResponse person = createPerson(client, "no-crop-person");

			expect(404, "Not Found", client.importPersonImage(person.getUuid(), new PersonImageImportRequest()
				.setDetectionUuid(detection.getUuid().toString())));
		}
	}

	@Test
	public void testMalformedUuidsAreBadRequests() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			PersonResponse person = createPerson(client, "malformed-person");

			expect(400, "Bad Request", client.importPersonImage(person.getUuid(), new PersonImageImportRequest()
				.setDetectionUuid("not-a-uuid")));
			expect(400, "Bad Request", client.setPersonAvatar(person.getUuid(), new PersonAvatarRequest()
				.setImageUuid("not-a-uuid")));
		}
	}

	/**
	 * Reading a person's pictures needs {@code READ_PERSON} and changing them needs {@code UPDATE_PERSON} - the same permissions as their name, because
	 * it is the same trust decision. A reader may look and may not touch.
	 */
	@Test
	public void testImagesFollowThePersonPermissions() throws Exception {
		UUID personUuid;
		UUID imageUuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			PersonResponse person = createPerson(admin, "perm-person");
			personUuid = person.getUuid();
			imageUuid = uploadImage(admin, personUuid).getUuid();
		}

		try (LoomHttpClient nobody = loginPermissionlessClient()) {
			expect(403, "Forbidden", nobody.listPersonImages(personUuid));
			expect(403, "Forbidden", nobody.deletePersonImage(personUuid, imageUuid));
			expect(403, "Forbidden", nobody.setPersonAvatar(personUuid, new PersonAvatarRequest()));
			expect(403, "Forbidden", nobody.importPersonImage(personUuid, new PersonImageImportRequest()
				.setDetectionUuid(UUID.randomUUID().toString())));
		}

		try (LoomHttpClient reader = loginClientWith("person-image-reader", READ_PERSON)) {
			assertEquals(1, reader.listPersonImages(personUuid).sync().body().getData().size(), "READ_PERSON is enough to look");
			expect(403, "Forbidden", reader.setPersonAvatar(personUuid, new PersonAvatarRequest()));
			expect(403, "Forbidden", reader.deletePersonImage(personUuid, imageUuid));
		}

		try (LoomHttpClient writer = loginClientWith("person-image-writer", READ_PERSON, UPDATE_PERSON)) {
			PersonResponse updated = writer.setPersonAvatar(personUuid, new PersonAvatarRequest()
				.setImageUuid(imageUuid.toString())).sync().body();
			assertNotNull(updated.getAvatarUrl(), "UPDATE_PERSON may choose the avatar");

			// Importing a crop is the one route that needs a second permission: it copies biometric content out of a detection, so being allowed to
			// edit a person is not on its own permission to read faces.
			expect(403, "Forbidden", writer.importPersonImage(personUuid, new PersonImageImportRequest()
				.setDetectionUuid(UUID.randomUUID().toString())));
		}

		try (LoomHttpClient importer = loginClientWith("person-image-importer", READ_PERSON, UPDATE_PERSON, READ_DETECTION)) {
			// Past the permission gate: the detection does not exist, so this is a 404 rather than a 403.
			expect(404, "Not Found", importer.importPersonImage(personUuid, new PersonImageImportRequest()
				.setDetectionUuid(UUID.randomUUID().toString())));
		}
	}

	// ---------------------------------------------------------------------------------------------

	private PersonResponse createPerson(LoomHttpClient client, String alias) throws LoomClientException {
		return client.createPerson(new PersonCreateRequest().setAlias(alias)).sync().body();
	}

	private PersonImageResponse uploadImage(LoomHttpClient client, UUID personUuid) throws Exception {
		return client.uploadPersonImage(personUuid, imageFile(), "image/jpeg", null).sync().body();
	}

	/**
	 * A distinct image per call: the bytes are content-addressed, so two identical files would share one attachment_binary row and the sizes asserted
	 * above would stop distinguishing anything.
	 */
	private File imageFile() throws Exception {
		File file = File.createTempFile("person-image-", ".jpg");
		file.deleteOnExit();
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, (int) (Math.random() * 0xFFFFFF));
		ImageIO.write(image, "jpg", file);
		return file;
	}

	private DetectionResponse createFaceDetection(LoomHttpClient client, UUID assetUuid) throws LoomClientException {
		DetectionBulkCreateRequest request = new DetectionBulkCreateRequest();
		request.getDetections().add(new DetectionCreateRequest()
			.setType("face")
			.setNodeKind("facedetect")
			.setDetectionIndex(0)
			.setFrameNumber(0)
			.setBboxX(0.25f)
			.setBboxY(0.15f)
			.setBboxWidth(0.1f)
			.setBboxHeight(0.2f)
			.setConfidence(0.95f));
		return client.bulkCreateAssetDetections(assetUuid, request).sync().body().getDetections().get(0);
	}

}
