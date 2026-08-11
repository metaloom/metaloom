package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.test.data.TestValues.ASSET_SHA512SUM;
import static io.metaloom.loom.test.data.TestValues.ASSET_UUID;
import static io.metaloom.loom.test.data.TestValues.IMAGE_MIMETYPE;
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
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.rest.model.share.ShareChallengeResponse;
import io.metaloom.loom.rest.model.share.ShareCreateRequest;
import io.metaloom.loom.rest.model.share.ShareResponse;
import io.metaloom.loom.rest.model.share.ShareSessionRequest;
import io.metaloom.loom.rest.model.share.ShareSessionResponse;
import io.metaloom.loom.rest.model.share.SharedAssetListResponse;
import io.metaloom.loom.rest.model.share.SharedAssetResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * The customer-facing area: {@code /api/v1/shares/:slug}.
 *
 * <p>
 * <b>The only endpoint test in the suite that never logs a visitor in</b>, because that is the feature: a member of the public opens a URL. The
 * clients here call {@code loom.httpClient()} and never {@code loginAdmin}, except where a link has to be created first.
 * </p>
 *
 * <p>
 * Most of this class is negative cases. The share routes are the one place in the API where a mistake is published to the open internet rather than
 * to somebody who already had an account, so what must <i>not</i> work is worth more coverage than what must.
 * </p>
 */
public class PublicShareEndpointTest extends AbstractEndpointTest {

	// ---------------------------------------------------------------------------------------
	// Getting in
	// ---------------------------------------------------------------------------------------

	/**
	 * The challenge tells a visitor only what the front door needs, and nothing about the material.
	 */
	@Test
	public void testChallengeLeaksNothing() throws Exception {
		ShareResponse share = createShare(request -> request.setPassword("wander-lamp-42"));

		try (LoomHttpClient guest = loom.httpClient()) {
			ShareChallengeResponse challenge = guest.loadShareChallenge(share.getSlug()).sync().body();
			assertEquals("ASSET", challenge.getTargetType());
			assertTrue(challenge.getPasswordRequired());
			assertFalse(challenge.getVisitorNameKnown());
			assertNull(challenge.getVisitorName(), "Nobody has opened it yet");
		}
	}

	/**
	 * An unknown slug and a revoked one are indistinguishable. A distinct status for "this used to exist" would turn the endpoint into an oracle.
	 */
	@Test
	public void testUnknownAndRevokedSlugsAreBoth404() throws Exception {
		ShareResponse share = createShare(request -> {
		});

		try (LoomHttpClient owner = loom.httpClient()) {
			loginAdmin(owner);
			owner.deleteShare(share.getUuid()).sync().body();
		}

		try (LoomHttpClient guest = loom.httpClient()) {
			expect(404, "Not Found", guest.loadShareChallenge(share.getSlug()));
			expect(404, "Not Found", guest.loadShareChallenge("neverExistedAtAll12"));
		}
	}

	/**
	 * An expired link is closed to visitors, and closed the same way an unknown one is.
	 */
	@Test
	public void testExpiredShareIs404() throws Exception {
		ShareResponse share = createShare(request -> request.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES)));

		try (LoomHttpClient guest = loom.httpClient()) {
			expect(404, "Not Found", guest.loadShareChallenge(share.getSlug()));
			expect(404, "Not Found", guest.openShare(share.getSlug(), new ShareSessionRequest()));
		}
	}

	/**
	 * An open link needs no password; a protected one refuses the wrong password with 401.
	 */
	@Test
	public void testPasswordIsEnforced() throws Exception {
		ShareResponse open = createShare(request -> {
		});
		ShareResponse locked = createShare(request -> request.setPassword("wander-lamp-42"));

		try (LoomHttpClient guest = loom.httpClient()) {
			ShareSessionResponse session = guest.openShare(open.getSlug(), named("Maria")).sync().body();
			assertNotNull(session.getSessionToken(), "An open link opens with no password at all");

			expect(401, "Unauthorized", guest.openShare(locked.getSlug(), named("Maria")));

			ShareSessionRequest wrong = named("Maria");
			wrong.setPassword("not-the-password");
			expect(401, "Unauthorized", guest.openShare(locked.getSlug(), wrong));

			ShareSessionRequest right = named("Maria");
			right.setPassword("wander-lamp-42");
			assertNotNull(guest.openShare(locked.getSlug(), right).sync().body().getSessionToken());
		}
	}

	/**
	 * The first visitor names the link; a later one does not rename it.
	 *
	 * <p>
	 * This is the whole of the chosen identity model. A second visitor silently overwriting the first one's name would be indistinguishable from
	 * working software until somebody read the review and found it attributed to the wrong person.
	 * </p>
	 */
	@Test
	public void testFirstVisitorNamesTheLink() throws Exception {
		ShareResponse share = createShare(request -> {
		});

		try (LoomHttpClient first = loom.httpClient()) {
			assertEquals("Maria", first.openShare(share.getSlug(), named("Maria")).sync().body().getVisitorName());
		}
		try (LoomHttpClient second = loom.httpClient()) {
			assertEquals("Maria", second.openShare(share.getSlug(), named("Someone Else")).sync().body().getVisitorName(),
				"A later visitor must not rename the link");
		}
		try (LoomHttpClient guest = loom.httpClient()) {
			ShareChallengeResponse challenge = guest.loadShareChallenge(share.getSlug()).sync().body();
			assertTrue(challenge.getVisitorNameKnown());
			assertEquals("Maria", challenge.getVisitorName());
		}
		try (LoomHttpClient owner = loom.httpClient()) {
			loginAdmin(owner);
			ShareResponse reloaded = owner.loadShare(share.getUuid()).sync().body();
			assertEquals("Maria", reloaded.getVisitorName());
			assertEquals(2, reloaded.getViewCount(), "Every redeemed session counts");
			assertNotNull(reloaded.getFirstVisitedAt());
		}
	}

	/**
	 * Skipping the name question stores "Anonymous" rather than leaving the link unnamed.
	 */
	@Test
	public void testSkippingTheNameStoresAnonymous() throws Exception {
		ShareResponse share = createShare(request -> {
		});
		try (LoomHttpClient guest = loom.httpClient()) {
			assertEquals("Anonymous", guest.openShare(share.getSlug(), new ShareSessionRequest()).sync().body().getVisitorName());
		}
	}

	// ---------------------------------------------------------------------------------------
	// Staying out
	// ---------------------------------------------------------------------------------------

	/**
	 * Without a session token, nothing behind the door is reachable - even though the routes themselves are unauthenticated.
	 */
	@Test
	public void testContentRoutesRequireASession() throws Exception {
		ShareResponse share = createShare(request -> {
		});
		try (LoomHttpClient guest = loom.httpClient()) {
			expect(401, "Unauthorized", guest.listSharedAssets(share.getSlug()));
			expect(401, "Unauthorized", guest.loadSharedAsset(share.getSlug(), ASSET_UUID));
			expect(401, "Unauthorized", guest.listSharedComments(share.getSlug()));
		}
	}

	/**
	 * A session issued for one link does not open another.
	 *
	 * <p>
	 * The token is signed by this server, so a signature check alone would accept it anywhere. It also names the slug it was issued for, and that is
	 * what stops one valid session from being a key to every link in the installation.
	 * </p>
	 */
	@Test
	public void testSessionIsBoundToItsOwnLink() throws Exception {
		ShareResponse mine = createShare(request -> {
		});
		ShareResponse theirs = createShare(request -> {
		});

		try (LoomHttpClient guest = loom.httpClient()) {
			String token = guest.openShare(mine.getSlug(), named("Maria")).sync().body().getSessionToken();
			guest.setShareSessionToken(token);

			assertNotNull(guest.listSharedAssets(mine.getSlug()).sync().body(), "The session opens its own link");
			expect(401, "Unauthorized", guest.listSharedAssets(theirs.getSlug()));
		}
	}

	/**
	 * A forged or corrupted token is refused.
	 */
	@Test
	public void testForgedTokenIsRefused() throws Exception {
		ShareResponse share = createShare(request -> {
		});
		try (LoomHttpClient guest = loom.httpClient()) {
			String token = guest.openShare(share.getSlug(), named("Maria")).sync().body().getSessionToken();

			guest.setShareSessionToken("garbage");
			expect(401, "Unauthorized", guest.listSharedAssets(share.getSlug()));

			// Right payload, wrong signature.
			guest.setShareSessionToken(token.substring(0, token.indexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
			expect(401, "Unauthorized", guest.listSharedAssets(share.getSlug()));
		}
	}

	/**
	 * <b>The most important test in the feature.</b>
	 *
	 * <p>
	 * The asset uuid arrives in the path and nothing else in the request constrains it. Without a membership check, one share link would be a read
	 * capability over every asset in the installation - a share of a holiday photo would serve any file whose uuid you could guess or had once seen.
	 * </p>
	 */
	@Test
	public void testShareCannotReachAnAssetItDoesNotContain() throws Exception {
		Asset outsider = storedAsset("outside-the-share.png");
		ShareResponse share = createShare(request -> {
		});

		try (LoomHttpClient guest = loom.httpClient()) {
			guest.setShareSessionToken(guest.openShare(share.getSlug(), named("Maria")).sync().body().getSessionToken());

			assertNotNull(guest.loadSharedAsset(share.getSlug(), ASSET_UUID).sync().body(), "The shared asset is reachable");

			// 404 rather than 403: answering "forbidden" would confirm the asset exists, which turns the link into
			// a probe for the uuids of material the visitor was never shown.
			expect(404, "Not Found", guest.loadSharedAsset(share.getSlug(), outsider.getUuid()));
		}
	}

	/**
	 * A collection share admits its current members and nothing else - and membership is resolved live, so removing an asset from the collection
	 * closes it off through every link to that collection.
	 */
	@Test
	public void testCollectionMembershipIsLive() throws Exception {
		Asset member = storedAsset("collection-member.png");

		ShareResponse share;
		try (LoomHttpClient owner = loom.httpClient()) {
			loginAdmin(owner);
			owner.addCollectionAsset(COLLECTION_UUID, member.getUuid()).sync().body();

			ShareCreateRequest request = new ShareCreateRequest();
			request.setTargetType("COLLECTION");
			request.setTargetUuid(COLLECTION_UUID);
			share = owner.createShare(request).sync().body();
		}

		try (LoomHttpClient guest = loom.httpClient()) {
			guest.setShareSessionToken(guest.openShare(share.getSlug(), named("Maria")).sync().body().getSessionToken());
			assertNotNull(guest.loadSharedAsset(share.getSlug(), member.getUuid()).sync().body(), "A member is reachable");
		}

		try (LoomHttpClient owner = loom.httpClient()) {
			loginAdmin(owner);
			owner.removeCollectionAsset(COLLECTION_UUID, member.getUuid()).sync().body();
		}

		try (LoomHttpClient guest = loom.httpClient()) {
			guest.setShareSessionToken(guest.openShare(share.getSlug(), named("Maria")).sync().body().getSessionToken());
			expect(404, "Not Found", guest.loadSharedAsset(share.getSlug(), member.getUuid()));
		}
	}

	// ---------------------------------------------------------------------------------------
	// What a visitor sees
	// ---------------------------------------------------------------------------------------

	/**
	 * An asset share lists exactly one asset, so the viewer has one code path for both kinds of link.
	 */
	@Test
	public void testAssetShareListsOneAsset() throws Exception {
		ShareResponse share = createShare(request -> {
		});
		try (LoomHttpClient guest = loom.httpClient()) {
			guest.setShareSessionToken(guest.openShare(share.getSlug(), named("Maria")).sync().body().getSessionToken());
			SharedAssetListResponse list = guest.listSharedAssets(share.getSlug()).sync().body();
			assertEquals(1, list.getData().size());
			assertEquals(ASSET_UUID, list.getData().get(0).getUuid());
		}
	}

	/**
	 * With {@code showMetadata} off the visitor still gets the file and its type, and nothing else.
	 */
	@Test
	public void testMetadataCanBeWithheld() throws Exception {
		ShareResponse open = createShare(request -> request.setShowMetadata(true));
		ShareResponse closed = createShare(request -> request.setShowMetadata(false));

		try (LoomHttpClient guest = loom.httpClient()) {
			guest.setShareSessionToken(guest.openShare(open.getSlug(), named("Maria")).sync().body().getSessionToken());
			SharedAssetResponse full = guest.loadSharedAsset(open.getSlug(), ASSET_UUID).sync().body();
			assertNotNull(full.getFilename());
			assertNotNull(full.getSize(), "Metadata is shown when the link allows it");
		}

		try (LoomHttpClient guest = loom.httpClient()) {
			guest.setShareSessionToken(guest.openShare(closed.getSlug(), named("Maria")).sync().body().getSessionToken());
			SharedAssetResponse bare = guest.loadSharedAsset(closed.getSlug(), ASSET_UUID).sync().body();
			assertNotNull(bare.getFilename(), "The file can still be identified");
			assertNotNull(bare.getMimeType(), "...and played");
			assertNull(bare.getSize(), "But the size is withheld");
			assertNull(bare.getCreated(), "...and so is everything else");
			assertNull(bare.getTitle());
			assertNull(bare.getDescription());
		}
	}

	/**
	 * The session response tells the viewer what it may render, so the UI never has to guess.
	 */
	@Test
	public void testSessionReportsCapabilities() throws Exception {
		ShareResponse share = createShare(request -> {
			request.setAllowDownload(false);
			request.setAllowComments(true);
			request.setAllowReactions(true);
			request.setAllowAnnotations(false);
		});

		try (LoomHttpClient guest = loom.httpClient()) {
			ShareSessionResponse session = guest.openShare(share.getSlug(), named("Maria")).sync().body();
			assertFalse(session.getAllowDownload());
			assertTrue(session.getAllowComments());
			assertTrue(session.getAllowReactions());
			assertFalse(session.getAllowAnnotations());
			assertEquals("ASSET", session.getTargetType());
			assertNotNull(session.getTargetName());
		}
	}

	// ---------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------

	private ShareSessionRequest named(String name) {
		return new ShareSessionRequest().setVisitorName(name);
	}

	/**
	 * Create a share of the fixture asset as the admin, and hand it back for the guest half of the test.
	 */
	private ShareResponse createShare(java.util.function.Consumer<ShareCreateRequest> customiser) throws LoomClientException {
		try (LoomHttpClient owner = loom.httpClient()) {
			loginAdmin(owner);
			ShareCreateRequest request = new ShareCreateRequest();
			request.setTargetType("ASSET");
			request.setTargetUuid(ASSET_UUID);
			customiser.accept(request);
			return owner.createShare(request).sync().body();
		}
	}

	private int assetCounter = 0;

	private Asset storedAsset(String filename) {
		int i = assetCounter++;
		String base = ASSET_SHA512SUM.toString().substring(0, 118);
		SHA512 sha = SHA512.fromString(base + String.format("%010x", (System.nanoTime() + i) & 0xFFFFFFFFFFL));
		Asset asset = daos().assetDao().createAsset(daos().userDao().load(adminUuid()), sha, IMAGE_MIMETYPE, filename, "test", 42L);
		daos().assetDao().store(asset);
		return asset;
	}
}
