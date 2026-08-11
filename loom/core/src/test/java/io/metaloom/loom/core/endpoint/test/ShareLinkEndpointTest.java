package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.db.model.perm.Permission.CREATE_SHARE;
import static io.metaloom.loom.db.model.perm.Permission.READ_ASSET;
import static io.metaloom.loom.db.model.perm.Permission.READ_COLLECTION;
import static io.metaloom.loom.db.model.perm.Permission.READ_SHARE;
import static io.metaloom.loom.test.data.TestValues.ASSET_UUID;
import static io.metaloom.loom.test.data.TestValues.COLLECTION_UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.share.ShareCreateRequest;
import io.metaloom.loom.rest.model.share.ShareListResponse;
import io.metaloom.loom.rest.model.share.ShareResponse;
import io.metaloom.loom.rest.model.share.ShareUpdateRequest;

/**
 * The owner-facing half of sharing: {@code /api/v1/share-links}.
 *
 * <p>
 * The customer-facing half has its own class, {@code PublicShareEndpointTest}, because none of the RBAC cases here apply to it.
 * </p>
 */
public class ShareLinkEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		ShareResponse created = createAssetShare(client, null);
		ShareResponse loaded = client.loadShare(created.getUuid()).sync().body();
		assertEquals(created.getUuid(), loaded.getUuid());
		assertEquals(created.getSlug(), loaded.getSlug());
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		ShareResponse share = createAssetShare(client, null);
		assertNotNull(share.getUuid());
		assertNotNull(share.getSlug());
		assertEquals(22, share.getSlug().length(), "The slug is 128 bits of base64url");
		assertFalse(share.getSlug().contains("."),
			"A dot in the slug would make UIService serve the static handler rather than the app");
		assertEquals("ASSET", share.getTargetType());
		assertEquals(ASSET_UUID, share.getTargetUuid());
		assertTrue(share.getAllowDownload(), "Downloading is on by default");
		assertFalse(share.getAllowComments(), "Guest feedback is off by default");
		assertEquals(0, share.getViewCount());
		assertEquals(0, share.getFeedbackCount());
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		ShareResponse share = createAssetShare(client, null);

		ShareUpdateRequest update = new ShareUpdateRequest();
		update.setAllowDownload(false);
		update.setAllowComments(true);
		ShareResponse updated = client.updateShare(share.getUuid(), update).sync().body();

		assertFalse(updated.getAllowDownload());
		assertTrue(updated.getAllowComments());
		assertTrue(updated.getShowMetadata(), "An absent field is left alone");
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		ShareResponse share = createAssetShare(client, null);
		client.deleteShare(share.getUuid()).sync().body();
		expect(404, "Not Found", client.loadShare(share.getUuid()));
		// The URL stops working at the same moment, which is the whole point of revoking.
		expect(404, "Not Found", client.loadShareChallenge(share.getSlug()));
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 30; i++) {
			createAssetShare(client, null);
		}
		ShareListResponse list = client.listShares().sync().body();
		assertEquals(25, list.getData().size(), "The default page size applies");
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		return client.createShare(assetShareRequest(null));
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadShare(COLLECTION_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listShares();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteShare(COLLECTION_UUID);
	}

	/**
	 * You may not publish what you are not allowed to look at.
	 *
	 * <p>
	 * CREATE_SHARE alone is not enough: a share link makes material readable to anybody holding a URL, so creating one requires READ on the target as
	 * well. Without this, {@code CREATE_SHARE} would be a way around every read restriction in the installation.
	 * </p>
	 */
	@Test
	public void testCreateRequiresReadOnTheTarget() throws Exception {
		try (LoomHttpClient client = loginClientWith("sharer-without-read", CREATE_SHARE)) {
			expect(403, "Forbidden", client.createShare(assetShareRequest(null)));
		}
		try (LoomHttpClient client = loginClientWith("reader-without-share", READ_ASSET)) {
			expect(403, "Forbidden", client.createShare(assetShareRequest(null)));
		}
		try (LoomHttpClient client = loginClientWith("proper-sharer", CREATE_SHARE, READ_ASSET, READ_SHARE)) {
			ShareResponse share = client.createShare(assetShareRequest(null)).sync().body();
			assertNotNull(share.getUuid(), "Holding both permissions is enough");
		}
	}

	/**
	 * Sharing a collection needs READ_COLLECTION, not READ_ASSET - the required set depends on the body.
	 */
	@Test
	public void testCollectionShareRequiresCollectionRead() throws Exception {
		ShareCreateRequest request = new ShareCreateRequest();
		request.setTargetType("COLLECTION");
		request.setTargetUuid(COLLECTION_UUID);

		try (LoomHttpClient client = loginClientWith("collection-sharer-wrong-perm", CREATE_SHARE, READ_ASSET)) {
			expect(403, "Forbidden", client.createShare(request));
		}
		try (LoomHttpClient client = loginClientWith("collection-sharer", CREATE_SHARE, READ_COLLECTION)) {
			ShareResponse share = client.createShare(request).sync().body();
			assertEquals("COLLECTION", share.getTargetType());
			assertEquals(COLLECTION_UUID, share.getTargetUuid());
		}
	}

	/**
	 * The generated password comes back exactly once and is never readable again.
	 *
	 * <p>
	 * Only the bcrypt hash is stored, so the create response is the sole opportunity to show it to the person who has to pass it on. A later read
	 * returning it would mean it was stored in clear.
	 * </p>
	 */
	@Test
	public void testPasswordIsReturnedOnceAndNeverAgain() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			ShareResponse created = createAssetShare(client, "wander-lamp-42");
			assertEquals("wander-lamp-42", created.getPassword(), "The create response carries the password once");
			assertTrue(created.getPasswordProtected());

			ShareResponse loaded = client.loadShare(created.getUuid()).sync().body();
			assertNull(loaded.getPassword(), "A later read must never return the password");
			assertTrue(loaded.getPasswordProtected(), "...but it still says the link is protected");

			ShareListResponse list = client.listShares().sync().body();
			list.getData().forEach(entry -> assertNull(entry.getPassword(), "No listing may leak a password"));
		}
	}

	/**
	 * A password can be replaced and removed. {@code removePassword} wins over a password sent in the same request.
	 */
	@Test
	public void testPasswordCanBeRemoved() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ShareResponse share = createAssetShare(client, "first-password-11");

			ShareUpdateRequest remove = new ShareUpdateRequest();
			remove.setRemovePassword(true);
			remove.setPassword("ignored-because-remove-wins");
			ShareResponse opened = client.updateShare(share.getUuid(), remove).sync().body();
			assertFalse(opened.getPasswordProtected(), "removePassword wins over a password in the same body");

			// And the link really is open now.
			assertFalse(client.loadShareChallenge(share.getSlug()).sync().body().getPasswordRequired());
		}
	}

	/**
	 * An expiry can be set and cleared, and an expired link reports itself as such to its owner - while answering 404 to a visitor.
	 */
	@Test
	public void testExpiry() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			ShareCreateRequest request = assetShareRequest(null);
			request.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
			ShareResponse lapsed = client.createShare(request).sync().body();

			assertTrue(client.loadShare(lapsed.getUuid()).sync().body().getExpired(),
				"The owner can see that a link has lapsed");
			expect(404, "Not Found", client.loadShareChallenge(lapsed.getSlug()));

			ShareUpdateRequest clear = new ShareUpdateRequest();
			clear.setClearExpiry(true);
			ShareResponse revived = client.updateShare(lapsed.getUuid(), clear).sync().body();
			assertNull(revived.getExpiresAt());
			assertFalse(revived.getExpired());
			assertNotNull(client.loadShareChallenge(revived.getSlug()).sync().body(), "...and it works again");
		}
	}

	/**
	 * The share URL is absolute, so it can be pasted straight into an email.
	 */
	@Test
	public void testUrlIsAbsolute() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			ShareResponse share = createAssetShare(client, null);
			assertNotNull(share.getUrl());
			assertTrue(share.getUrl().startsWith("http"), "The link must be pasteable as-is, not root-relative: " + share.getUrl());
			assertTrue(share.getUrl().endsWith("/ui/share/" + share.getSlug()), "Unexpected share URL " + share.getUrl());
		}
	}

	/**
	 * The links pointing at one asset or one collection are reachable from that asset or collection.
	 */
	@Test
	public void testListByTarget() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			ShareResponse assetShare = createAssetShare(client, null);

			ShareCreateRequest collectionRequest = new ShareCreateRequest();
			collectionRequest.setTargetType("COLLECTION");
			collectionRequest.setTargetUuid(COLLECTION_UUID);
			ShareResponse collectionShare = client.createShare(collectionRequest).sync().body();

			ShareListResponse byAsset = client.listAssetShares(ASSET_UUID).sync().body();
			assertTrue(byAsset.getData().stream().anyMatch(s -> s.getUuid().equals(assetShare.getUuid())));
			assertTrue(byAsset.getData().stream().noneMatch(s -> s.getUuid().equals(collectionShare.getUuid())));

			ShareListResponse byCollection = client.listCollectionShares(COLLECTION_UUID).sync().body();
			assertTrue(byCollection.getData().stream().anyMatch(s -> s.getUuid().equals(collectionShare.getUuid())));
		}
	}

	/**
	 * Every share route refuses an anonymous caller. This is the owner-facing half; being able to reach it without a token would make the
	 * customer-facing half's careful authorization pointless.
	 */
	@Test
	public void testOwnerRoutesRequireAuthentication() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			// No login at all.
			expect(401, "Unauthorized", client.listShares());
			expect(401, "Unauthorized", client.loadShare(COLLECTION_UUID));
			expect(401, "Unauthorized", client.createShare(assetShareRequest(null)));
			expect(401, "Unauthorized", client.deleteShare(COLLECTION_UUID));
		}
	}

	private ShareCreateRequest assetShareRequest(String password) {
		ShareCreateRequest request = new ShareCreateRequest();
		request.setTargetType("ASSET");
		request.setTargetUuid(ASSET_UUID);
		request.setPassword(password);
		return request;
	}

	private ShareResponse createAssetShare(LoomHttpClient client, String password) throws LoomClientException {
		return client.createShare(assetShareRequest(password)).sync().body();
	}
}
