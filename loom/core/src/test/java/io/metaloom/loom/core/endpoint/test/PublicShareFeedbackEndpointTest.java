package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.test.data.TestValues.ASSET_SHA512SUM;
import static io.metaloom.loom.test.data.TestValues.ASSET_UUID;
import static io.metaloom.loom.test.data.TestValues.IMAGE_MIMETYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.share.ShareAnnotationRequest;
import io.metaloom.loom.rest.model.share.ShareAnnotationResponse;
import io.metaloom.loom.rest.model.share.ShareCommentRequest;
import io.metaloom.loom.rest.model.share.ShareCommentResponse;
import io.metaloom.loom.rest.model.share.ShareCreateRequest;
import io.metaloom.loom.rest.model.share.ShareFeedbackResponse;
import io.metaloom.loom.rest.model.share.ShareReactionRequest;
import io.metaloom.loom.rest.model.share.ShareReactionResponse;
import io.metaloom.loom.rest.model.share.ShareResponse;
import io.metaloom.loom.rest.model.share.ShareSessionRequest;
import io.metaloom.utils.hash.SHA512;

/**
 * Phase 2: what a customer says back through a share link.
 *
 * <p>
 * Three things are being pinned here. That each capability is off unless the link grants it; that a visitor's writes stay inside the link they hold;
 * and that the author of a piece of feedback is taken from the share row rather than from the request - a name a visitor could set is a name a
 * visitor could set to somebody else's.
 * </p>
 */
public class PublicShareFeedbackEndpointTest extends AbstractEndpointTest {

	/**
	 * Every capability is off by default and refused with 403 - not 404, because the visitor is looking at the link and is entitled to know it exists.
	 */
	@Test
	public void testFeedbackIsRefusedUnlessTheLinkAllowsIt() throws Exception {
		ShareResponse share = createShare(request -> {
		});
		try (LoomHttpClient guest = openAs(share, "Maria")) {
			expect(403, "Forbidden", guest.createSharedComment(share.getSlug(), comment("nope")));
			expect(403, "Forbidden", guest.createSharedReaction(share.getSlug(), approveAsset()));
			expect(403, "Forbidden", guest.createSharedAnnotation(share.getSlug(), temporalMark(3.5)));
		}
	}

	/**
	 * A comment round trip, with the author taken from the link rather than from the body.
	 */
	@Test
	public void testCommentRoundTrip() throws Exception {
		ShareResponse share = createShare(request -> request.setAllowComments(true));

		try (LoomHttpClient guest = openAs(share, "Maria from Acme")) {
			ShareCommentResponse created = guest.createSharedComment(share.getSlug(),
				comment("The second cut runs long")).sync().body();
			assertNotNull(created.getUuid());
			assertEquals("The second cut runs long", created.getText());
			assertEquals("Maria from Acme", created.getAuthorName(), "The author comes from the link, not the request");
			assertEquals(ASSET_UUID, created.getAssetUuid(), "An asset share implies its own asset");

			ShareCommentRequest edit = new ShareCommentRequest();
			edit.setText("The second cut runs much too long");
			assertEquals("The second cut runs much too long",
				guest.updateSharedComment(share.getSlug(), created.getUuid(), edit).sync().body().getText());

			assertEquals(1, guest.listSharedComments(share.getSlug()).sync().body().getData().size());

			guest.deleteSharedComment(share.getSlug(), created.getUuid()).sync().body();
			assertTrue(guest.listSharedComments(share.getSlug()).sync().body().getData().isEmpty());
		}
	}

	/**
	 * A mark carries normalised coordinates and sub-second times, and comes back with both intact.
	 */
	@Test
	public void testAnnotationRoundTrip() throws Exception {
		ShareResponse share = createShare(request -> request.setAllowAnnotations(true));

		try (LoomHttpClient guest = openAs(share, "Maria")) {
			ShareAnnotationRequest request = new ShareAnnotationRequest();
			request.setAssetUuid(ASSET_UUID);
			request.setKind("SPATIOTEMPORAL");
			request.setTimeFrom(14.25);
			request.setTimeTo(19.5);
			request.setAreaX(0.42);
			request.setAreaY(0.18);
			request.setAreaWidth(0.16);
			request.setAreaHeight(0.22);
			request.setText("The logo is clipped here");

			ShareAnnotationResponse created = guest.createSharedAnnotation(share.getSlug(), request).sync().body();
			assertEquals(14.25, created.getTimeFrom(), "Sub-second precision is the reason this is a float");
			assertEquals(19.5, created.getTimeTo());
			assertEquals(0.42, created.getAreaX());
			assertEquals("Maria", created.getAuthorName());

			assertEquals(1, guest.listSharedAnnotations(share.getSlug()).sync().body().getData().size());
		}
	}

	/**
	 * Geometry that contradicts the declared kind is a 400 naming the field, not a 500 naming a database constraint.
	 */
	@Test
	public void testAnnotationGeometryIsValidated() throws Exception {
		ShareResponse share = createShare(request -> request.setAllowAnnotations(true));

		try (LoomHttpClient guest = openAs(share, "Maria")) {
			ShareAnnotationRequest noRegion = new ShareAnnotationRequest();
			noRegion.setAssetUuid(ASSET_UUID);
			noRegion.setKind("SPATIAL");
			expect(400, "Bad Request", guest.createSharedAnnotation(share.getSlug(), noRegion));

			ShareAnnotationRequest outsideTheFrame = new ShareAnnotationRequest();
			outsideTheFrame.setAssetUuid(ASSET_UUID);
			outsideTheFrame.setKind("SPATIAL");
			outsideTheFrame.setAreaX(1.4);
			outsideTheFrame.setAreaY(0.1);
			outsideTheFrame.setAreaWidth(0.2);
			outsideTheFrame.setAreaHeight(0.2);
			expect(400, "Bad Request", guest.createSharedAnnotation(share.getSlug(), outsideTheFrame));

			ShareAnnotationRequest unknownKind = new ShareAnnotationRequest();
			unknownKind.setAssetUuid(ASSET_UUID);
			unknownKind.setKind("SOMETHING_ELSE");
			expect(400, "Bad Request", guest.createSharedAnnotation(share.getSlug(), unknownKind));
		}
	}

	/**
	 * Reacting twice the same way is one row, not an error - a double-clicked button is a normal thing for a person to do.
	 */
	@Test
	public void testReactionIsIdempotent() throws Exception {
		ShareResponse share = createShare(request -> request.setAllowReactions(true));

		try (LoomHttpClient guest = openAs(share, "Maria")) {
			ShareReactionResponse first = guest.createSharedReaction(share.getSlug(), approveAsset()).sync().body();
			assertNotNull(first);
			guest.createSharedReaction(share.getSlug(), approveAsset()).sync().body();

			assertEquals(1, guest.listSharedReactions(share.getSlug()).sync().body().getData().size(),
				"The same reaction twice is one row");
		}
	}

	/**
	 * A reaction must name exactly one subject.
	 */
	@Test
	public void testReactionNeedsExactlyOneSubject() throws Exception {
		ShareResponse share = createShare(request -> request.setAllowReactions(true));

		try (LoomHttpClient guest = openAs(share, "Maria")) {
			ShareReactionRequest none = new ShareReactionRequest();
			none.setType("APPROVE");
			expect(400, "Bad Request", guest.createSharedReaction(share.getSlug(), none));

			ShareReactionRequest unknownType = approveAsset();
			unknownType.setType("SHRUG");
			expect(400, "Bad Request", guest.createSharedReaction(share.getSlug(), unknownType));
		}
	}

	/**
	 * A visitor cannot write about an asset the link does not contain.
	 */
	@Test
	public void testCommentCannotNameAnAssetOutsideTheShare() throws Exception {
		Asset outsider = storedAsset("outsider.png");
		ShareResponse share = createShare(request -> request.setAllowComments(true));

		try (LoomHttpClient guest = openAs(share, "Maria")) {
			ShareCommentRequest request = comment("about something else entirely");
			request.setAssetUuid(outsider.getUuid());
			expect(404, "Not Found", guest.createSharedComment(share.getSlug(), request));
		}
	}

	/**
	 * One link cannot read, edit or delete another link's feedback, even knowing the uuid.
	 *
	 * <p>
	 * The loaders are all scoped by share for exactly this reason - a guest request arrives authenticated by nothing but a slug, so resolving a row by
	 * uuid alone would make every comment in the installation addressable from any link.
	 * </p>
	 */
	@Test
	public void testOneLinkCannotTouchAnothersFeedback() throws Exception {
		ShareResponse mine = createShare(request -> request.setAllowComments(true));
		ShareResponse theirs = createShare(request -> request.setAllowComments(true));

		ShareCommentResponse comment;
		try (LoomHttpClient guest = openAs(mine, "Maria")) {
			comment = guest.createSharedComment(mine.getSlug(), comment("mine only")).sync().body();
		}

		try (LoomHttpClient intruder = openAs(theirs, "Jon")) {
			assertTrue(intruder.listSharedComments(theirs.getSlug()).sync().body().getData().isEmpty(),
				"Another link's comments are not listed");

			ShareCommentRequest edit = new ShareCommentRequest();
			edit.setText("rewritten by somebody else");
			expect(404, "Not Found", intruder.updateSharedComment(theirs.getSlug(), comment.getUuid(), edit));
			expect(404, "Not Found", intruder.deleteSharedComment(theirs.getSlug(), comment.getUuid()));
		}
	}

	/**
	 * A reply attaches to the root of the thread. Replying to a reply does not nest a third level.
	 */
	@Test
	public void testRepliesAreOneLevelDeep() throws Exception {
		ShareResponse share = createShare(request -> request.setAllowComments(true));

		try (LoomHttpClient guest = openAs(share, "Maria")) {
			ShareCommentResponse root = guest.createSharedComment(share.getSlug(), comment("root")).sync().body();

			ShareCommentRequest replyRequest = comment("reply");
			replyRequest.setParentUuid(root.getUuid());
			ShareCommentResponse reply = guest.createSharedComment(share.getSlug(), replyRequest).sync().body();
			assertEquals(root.getUuid(), reply.getParentUuid());

			ShareCommentRequest nestedRequest = comment("reply to the reply");
			nestedRequest.setParentUuid(reply.getUuid());
			ShareCommentResponse nested = guest.createSharedComment(share.getSlug(), nestedRequest).sync().body();
			assertEquals(root.getUuid(), nested.getParentUuid(),
				"A reply to a reply attaches to the root rather than starting a third level");
		}
	}

	/**
	 * The owner reads everything the visitor said in one request - and needs their own permission to do it.
	 */
	@Test
	public void testOwnerReadsTheFeedback() throws Exception {
		ShareResponse share = createShare(request -> {
			request.setAllowComments(true);
			request.setAllowReactions(true);
			request.setAllowAnnotations(true);
		});

		try (LoomHttpClient guest = openAs(share, "Maria from Acme")) {
			guest.createSharedComment(share.getSlug(), comment("looks good")).sync().body();
			guest.createSharedReaction(share.getSlug(), approveAsset()).sync().body();
			guest.createSharedAnnotation(share.getSlug(), temporalMark(12.5)).sync().body();
		}

		try (LoomHttpClient owner = loom.httpClient()) {
			loginAdmin(owner);
			ShareFeedbackResponse feedback = owner.loadShareFeedback(share.getUuid()).sync().body();
			assertEquals("Maria from Acme", feedback.getVisitorName());
			assertEquals(1, feedback.getComments().size());
			assertEquals(1, feedback.getReactions().size());
			assertEquals(1, feedback.getAnnotations().size());

			assertEquals(3, owner.loadShare(share.getUuid()).sync().body().getFeedbackCount(),
				"The share listing reports how much came back");
		}

		try (LoomHttpClient nobody = loginPermissionlessClient()) {
			expect(403, "Forbidden", nobody.loadShareFeedback(share.getUuid()));
		}
	}

	/**
	 * Revoking a link removes what was said through it. The owner keeps nothing they could not attribute.
	 */
	@Test
	public void testRevokingALinkRemovesItsFeedback() throws Exception {
		ShareResponse share = createShare(request -> request.setAllowComments(true));

		try (LoomHttpClient guest = openAs(share, "Maria")) {
			guest.createSharedComment(share.getSlug(), comment("about to disappear")).sync().body();
		}

		try (LoomHttpClient owner = loom.httpClient()) {
			loginAdmin(owner);
			assertEquals(1, owner.loadShareFeedback(share.getUuid()).sync().body().getComments().size());
			owner.deleteShare(share.getUuid()).sync().body();
			expect(404, "Not Found", owner.loadShareFeedback(share.getUuid()));
		}
	}

	/**
	 * Feedback left through a link outlives the account that made the link. The share is not deleted with the user, so neither is what a customer
	 * said through it.
	 */
	@Test
	public void testFeedbackSurvivesTheOwnerAccount() throws Exception {
		ShareResponse share = createShare(request -> request.setAllowComments(true));

		try (LoomHttpClient guest = openAs(share, "Maria")) {
			guest.createSharedComment(share.getSlug(), comment("still here afterwards")).sync().body();
		}

		// Reassign the link to a throwaway account and delete that account. Created directly rather than through
		// loginClientWith, which also builds a role and a group - and `group.creator_uuid` has no cascade, so a user
		// provisioned that way cannot be deleted at all. The point of this test is the share FK, not that one.
		User owner = daos().userDao().createUser(adminUuid(), "share-owner-to-delete-" + System.nanoTime());
		daos().userDao().store(owner);
		Share stored = daos().shareDao().load(share.getUuid());
		stored.setCreatorUuid(owner.getUuid());
		stored.setEditorUuid(owner.getUuid());
		daos().shareDao().update(stored);

		daos().userDao().delete(owner.getUuid());

		try (LoomHttpClient guest = loom.httpClient()) {
			assertNotNull(guest.loadShareChallenge(share.getSlug()).sync().body(), "The link still opens");
		}
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			ShareResponse orphaned = admin.loadShare(share.getUuid()).sync().body();
			assertNull(orphaned.getStatus().getCreator(), "The owner column is emptied, the link is not");
			assertEquals(1, admin.loadShareFeedback(share.getUuid()).sync().body().getComments().size(),
				"What the customer said outlives the account that invited them");
		}
	}

	// --- Helpers ---

	private ShareCommentRequest comment(String text) {
		return new ShareCommentRequest().setText(text);
	}

	private ShareReactionRequest approveAsset() {
		return new ShareReactionRequest().setType("APPROVE").setAssetUuid(ASSET_UUID);
	}

	private ShareAnnotationRequest temporalMark(double at) {
		return new ShareAnnotationRequest().setAssetUuid(ASSET_UUID).setKind("TEMPORAL").setTimeFrom(at);
	}

	/**
	 * A guest client that has already opened the link and is holding its session.
	 */
	private LoomHttpClient openAs(ShareResponse share, String name) throws LoomClientException {
		LoomHttpClient guest = loom.httpClient();
		guest.setShareSessionToken(
			guest.openShare(share.getSlug(), new ShareSessionRequest().setVisitorName(name)).sync().body().getSessionToken());
		return guest;
	}

	private ShareResponse createShare(Consumer<ShareCreateRequest> customiser) throws LoomClientException {
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
