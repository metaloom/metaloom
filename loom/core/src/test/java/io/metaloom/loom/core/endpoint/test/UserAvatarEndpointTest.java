package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.READ_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.metaloom.loom.rest.model.user.UserAvatarResponse;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserResponse;

/**
 * Covers {@code /users/:uuid/avatar} and {@code /me/avatar}.
 *
 * <p>
 * Two properties matter more than the CRUD. First, an account has exactly <em>one</em> picture, enforced by a partial unique index rather than by
 * convention, so a second upload must replace rather than accumulate - {@link #testASecondUploadReplacesTheFirst()}. Second, the {@code /me} form has
 * to work for a user holding neither {@code READ_USER} nor {@code UPDATE_USER}, which is every non-administrator and therefore almost everybody who
 * will ever open the profile screen - {@link #testAUserMayChangeTheirOwnPictureWithoutUserPermissions()}.
 * </p>
 */
public class UserAvatarEndpointTest extends AbstractEndpointTest {

	@Test
	public void testUploadedAvatarIsReadableAndDownloadable() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UserResponse user = createUser(client, "avatar-target");

			UserAvatarResponse avatar = client.uploadUserAvatar(user.getUuid(), imageFile(), "image/jpeg", null).sync().body();
			assertNotNull(avatar.getUuid());
			assertEquals("image/jpeg", avatar.getMimeType());
			assertTrue(avatar.getSize() > 0, "the stored size is the uploaded size");
			assertEquals("/api/v1/users/" + user.getUuid() + "/avatar/data", avatar.getUrl(),
				"the response carries a URL, so no caller has to know how an account picture is addressed");

			assertEquals(avatar.getUuid(), client.loadUserAvatar(user.getUuid()).sync().body().getUuid());

			// The URL is what the UI renders in an <img src> beside a username, so it has to be on the user record too.
			assertEquals(avatar.getUrl(), client.loadUser(user.getUuid()).sync().body().getAvatarUrl());

			try (var response = client.downloadUserAvatar(user.getUuid()).sync().body()) {
				assertEquals(200, response.code());
				assertTrue(response.getStream().readAllBytes().length > 0, "the picture must have bytes");
				assertEquals("image/jpeg", response.getContentType());
			}
		}
	}

	/**
	 * The cardinality rule. A person accumulates a gallery because face detection keeps finding them; an account does not.
	 */
	@Test
	public void testASecondUploadReplacesTheFirst() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UserResponse user = createUser(client, "avatar-replacer");

			UserAvatarResponse first = client.uploadUserAvatar(user.getUuid(), imageFile(), "image/jpeg", null).sync().body();
			UserAvatarResponse second = client.uploadUserAvatar(user.getUuid(), imageFile(), "image/jpeg", null).sync().body();

			assertNotEquals(first.getUuid(), second.getUuid(), "a replacement is a new row, not an edit of the old one");
			assertEquals(second.getUuid(), client.loadUserAvatar(user.getUuid()).sync().body().getUuid(),
				"the account points at the new picture");
			// The old row is gone, so addressing it is a 404 rather than a second picture in a gallery.
			assertNotNull(client.loadUser(user.getUuid()).sync().body().getAvatarUrl());
		}
	}

	@Test
	public void testDeletingTheAvatarLeavesTheAccount() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UserResponse user = createUser(client, "avatar-deleter");
			client.uploadUserAvatar(user.getUuid(), imageFile(), "image/jpeg", null).sync().body();

			client.deleteUserAvatar(user.getUuid()).sync();

			UserResponse reloaded = client.loadUser(user.getUuid()).sync().body();
			assertNotNull(reloaded.getUuid(), "deleting the picture must not delete the account");
			assertNull(reloaded.getAvatarUrl(), "and it must clear the pointer, which the FK does with ON DELETE SET NULL");

			expect(404, "Not Found", client.loadUserAvatar(user.getUuid()));
			assertMissingBytes(client, user.getUuid());
		}
	}

	@Test
	public void testAnAccountWithNoPictureIsNotFoundRatherThanEmpty() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UserResponse user = createUser(client, "avatar-less");

			assertNull(client.loadUser(user.getUuid()).sync().body().getAvatarUrl());
			expect(404, "Not Found", client.loadUserAvatar(user.getUuid()));
			assertMissingBytes(client, user.getUuid());
		}
	}

	/**
	 * The reason {@code /me/avatar} exists at all.
	 *
	 * <p>
	 * {@code UPDATE_USER} is the permission to edit anybody's account. Requiring it to change your own picture would mean the profile screen worked
	 * for administrators only.
	 * </p>
	 */
	@Test
	public void testAUserMayChangeTheirOwnPictureWithoutUserPermissions() throws Exception {
		try (LoomHttpClient client = loginClientWith("avatar-self-service")) {
			UserAvatarResponse mine = client.uploadMyAvatar(imageFile(), "image/jpeg", null).sync().body();
			assertNotNull(mine.getUuid());

			assertEquals(mine.getUuid(), client.loadMyAvatar().sync().body().getUuid());
			try (var response = client.downloadMyAvatar().sync().body()) {
				assertEquals(200, response.code());
				assertTrue(response.getStream().readAllBytes().length > 0);
			}

			// Even read through /me, the URL is the /users form: it is rendered in other people's browsers,
			// where a self-relative one would resolve to their own face.
			assertTrue(mine.getUrl().startsWith("/api/v1/users/"), "the URL must not be the /me form: " + mine.getUrl());

			client.deleteMyAvatar().sync();
			expect(404, "Not Found", client.loadMyAvatar());
		}
	}

	/**
	 * Somebody else's picture is an administrative act, and the self-exemption must not leak into it.
	 */
	@Test
	public void testChangingAnotherAccountsPictureNeedsUpdateUser() throws Exception {
		UUID targetUuid;
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			targetUuid = createUser(admin, "avatar-victim").getUuid();
			admin.uploadUserAvatar(targetUuid, imageFile(), "image/jpeg", null).sync();
		}

		try (LoomHttpClient reader = loginClientWith("avatar-reader", READ_USER)) {
			assertNotNull(reader.loadUserAvatar(targetUuid).sync().body().getUuid(), "READ_USER is enough to look");
			expect(403, "Forbidden", reader.uploadUserAvatar(targetUuid, imageFile(), "image/jpeg", null));
			expect(403, "Forbidden", reader.deleteUserAvatar(targetUuid));
		}
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * A download request hands back the response rather than throwing on a non-2xx, so the status has to be read off it - {@code expect} would see a
	 * request that "succeeded".
	 */
	private void assertMissingBytes(LoomHttpClient client, UUID userUuid) throws Exception {
		try (var response = client.downloadUserAvatar(userUuid).sync().body()) {
			assertEquals(404, response.code(), "the bytes of an account with no picture must not be served");
		}
	}

	private UserResponse createUser(LoomHttpClient client, String prefix) throws LoomClientException {
		return client.createUser(new UserCreateRequest().setUsername(prefix + "-" + UUID.randomUUID())).sync().body();
	}

	/**
	 * A distinct image per call: the bytes are content-addressed, so two identical files would share one {@code attachment_binary} row and a
	 * replacement would be indistinguishable from a no-op.
	 */
	private File imageFile() throws Exception {
		File file = File.createTempFile("user-avatar-", ".jpg");
		file.deleteOnExit();
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
		image.setRGB(0, 0, (int) (Math.random() * 0xFFFFFF));
		ImageIO.write(image, "jpg", file);
		return file;
	}
}
