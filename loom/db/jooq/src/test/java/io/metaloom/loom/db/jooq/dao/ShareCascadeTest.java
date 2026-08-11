package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.collection.Collection;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareAnnotation;
import io.metaloom.loom.db.model.share.ShareAnnotationKind;
import io.metaloom.loom.db.model.share.ShareComment;
import io.metaloom.loom.db.model.share.ShareReaction;
import io.metaloom.loom.db.model.share.ShareReactionType;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.utils.hash.SHA512;

/**
 * Delete-cascade behaviour of the share tables.
 *
 * <p>
 * Three rules are asserted here, and each one is a deliberate decision rather than a default:
 * </p>
 *
 * <ol>
 * <li><b>Deleting a user must not delete their shares.</b> This is the stated requirement and the reason {@code share.creator_uuid} is nullable with
 * {@code ON DELETE SET NULL}, against the grain of every other audit column in the schema. A link handed to a client keeps working when the editor who
 * made it leaves.</li>
 * <li><b>Deleting the target deletes the share.</b> A link to an asset or collection that no longer exists can only ever render an error; leaving the
 * row would keep a dead URL answering 200 with an empty page.</li>
 * <li><b>Deleting a share takes its feedback with it and nothing else.</b> Every case creates a second, untouched share carrying an identical set of
 * rows and asserts it survives, which is what distinguishes a correct cascade from one join column too wide.</li>
 * </ol>
 */
public class ShareCascadeTest extends AbstractJooqTest {

	/**
	 * The requirement: deleting a user empties the owner column but leaves the link working.
	 */
	@Test
	public void testDeletingUserKeepsShare() {
		User owner = userDao().createUser(adminUser().getUuid(), "share_owner_" + System.nanoTime());
		userDao().store(owner);
		User bystander = userDao().createUser(adminUser().getUuid(), "share_bystander_" + System.nanoTime());
		userDao().store(bystander);

		Share ownedShare = shareDao().createAssetShare(owner.getUuid(), asset().getUuid(), slug("owned"));
		shareDao().store(ownedShare);
		Share otherShare = shareDao().createAssetShare(bystander.getUuid(), asset().getUuid(), slug("other"));
		shareDao().store(otherShare);

		userDao().delete(owner.getUuid());

		Share survivor = shareDao().load(ownedShare.getUuid());
		assertNotNull(survivor, "Deleting a user must NOT delete their shares - a link handed to a client outlives its author");
		assertNull(survivor.getCreatorUuid(), "The owner column is emptied rather than the row being removed");
		assertNotNull(shareDao().loadBySlug(ownedShare.getSlug()), "The link still resolves, which is the whole point");

		Share untouched = shareDao().load(otherShare.getUuid());
		assertNotNull(untouched, "Another user's share is unaffected");
		assertEquals(bystander.getUuid(), untouched.getCreatorUuid(), "...and keeps its own owner");
	}

	/**
	 * The share dies with the thing it points at.
	 */
	@Test
	public void testDeletingAssetDeletesItsShares() {
		User user = dummyUser();
		Asset victim = dummyAsset(user);
		Asset bystander = dummyAsset(user);

		Share victimShare = shareDao().createAssetShare(user.getUuid(), victim.getUuid(), slug("victim_asset"));
		shareDao().store(victimShare);
		Share bystanderShare = shareDao().createAssetShare(user.getUuid(), bystander.getUuid(), slug("bystander_asset"));
		shareDao().store(bystanderShare);

		assetDao().delete(victim.getUuid());

		assertNull(shareDao().load(victimShare.getUuid()), "A share of a deleted asset must cascade away");
		assertNotNull(shareDao().load(bystanderShare.getUuid()), "The other asset's share must survive");
	}

	@Test
	public void testDeletingCollectionDeletesItsShares() {
		User user = dummyUser();
		Collection victim = collectionDao().createCollection(user, "cascade_share_victim_" + System.nanoTime());
		collectionDao().store(victim);
		Collection bystander = collectionDao().createCollection(user, "cascade_share_bystander_" + System.nanoTime());
		collectionDao().store(bystander);

		Share victimShare = shareDao().createCollectionShare(user.getUuid(), victim.getUuid(), slug("victim_coll"));
		shareDao().store(victimShare);
		Share bystanderShare = shareDao().createCollectionShare(user.getUuid(), bystander.getUuid(), slug("bystander_coll"));
		shareDao().store(bystanderShare);

		collectionDao().delete(victim.getUuid());

		assertNull(shareDao().load(victimShare.getUuid()), "A share of a deleted collection must cascade away");
		assertNotNull(shareDao().load(bystanderShare.getUuid()), "The other collection's share must survive");
	}

	/**
	 * Revoking a link removes everything said through it - and nothing said through any other link.
	 */
	@Test
	public void testDeletingShareCascadesItsFeedbackOnly() {
		User user = dummyUser();
		Share victim = shareDao().createAssetShare(user.getUuid(), asset().getUuid(), slug("feedback_victim"));
		shareDao().store(victim);
		Share bystander = shareDao().createAssetShare(user.getUuid(), asset().getUuid(), slug("feedback_bystander"));
		shareDao().store(bystander);

		seedFeedback(victim);
		seedFeedback(bystander);

		assertEquals(1, shareFeedbackDao().countComments(victim.getUuid()));
		assertEquals(1, shareFeedbackDao().countAnnotations(victim.getUuid()));
		assertEquals(1, shareFeedbackDao().countReactions(victim.getUuid()));

		shareDao().delete(victim.getUuid());

		assertEquals(0, shareFeedbackDao().countComments(victim.getUuid()), "Comments left through the link go with it");
		assertEquals(0, shareFeedbackDao().countAnnotations(victim.getUuid()), "Annotations go with it");
		assertEquals(0, shareFeedbackDao().countReactions(victim.getUuid()), "Reactions go with it");

		assertEquals(1, shareFeedbackDao().countComments(bystander.getUuid()), "The other link's comments must survive");
		assertEquals(1, shareFeedbackDao().countAnnotations(bystander.getUuid()), "...and its annotations");
		assertEquals(1, shareFeedbackDao().countReactions(bystander.getUuid()), "...and its reactions");

		assertNotNull(assetDao().load(asset().getUuid()), "The asset itself is untouched by revoking a link to it");
	}

	/**
	 * Deleting an annotation takes the comments hanging off it, because a comment reading "this logo" is meaningless once the box it names is gone.
	 */
	@Test
	public void testDeletingAnnotationCascadesItsComments() {
		User user = dummyUser();
		Share share = shareDao().createAssetShare(user.getUuid(), asset().getUuid(), slug("annotation_cascade"));
		shareDao().store(share);

		ShareAnnotation annotation = shareFeedbackDao().createAnnotation(share.getUuid(), asset().getUuid(), ShareAnnotationKind.TEMPORAL, "Maria");
		annotation.setTimeFrom(14.5);
		shareFeedbackDao().storeAnnotation(annotation);

		ShareComment attached = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "the cut here is early");
		attached.setShareAnnotationUuid(annotation.getUuid());
		shareFeedbackDao().storeComment(attached);

		ShareComment freeStanding = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "otherwise fine");
		shareFeedbackDao().storeComment(freeStanding);

		shareFeedbackDao().deleteAnnotation(annotation.getUuid());

		assertNull(shareFeedbackDao().loadComment(share.getUuid(), attached.getUuid()), "A comment on a deleted mark goes with the mark");
		assertNotNull(shareFeedbackDao().loadComment(share.getUuid(), freeStanding.getUuid()), "A free-standing comment is unaffected");
	}

	/**
	 * Deleting a comment takes its replies and its reactions.
	 */
	@Test
	public void testDeletingCommentCascadesRepliesAndReactions() {
		User user = dummyUser();
		Share share = shareDao().createAssetShare(user.getUuid(), asset().getUuid(), slug("comment_cascade"));
		shareDao().store(share);

		ShareComment root = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "root");
		shareFeedbackDao().storeComment(root);
		ShareComment reply = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "reply");
		reply.setParentUuid(root.getUuid());
		shareFeedbackDao().storeComment(reply);

		ShareReaction onRoot = shareFeedbackDao().createReaction(share.getUuid(), ShareReactionType.THUMBSUP, "Maria");
		onRoot.setShareCommentUuid(root.getUuid());
		shareFeedbackDao().storeReaction(onRoot);

		ShareComment survivor = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "unrelated");
		shareFeedbackDao().storeComment(survivor);

		shareFeedbackDao().deleteComment(root.getUuid());

		assertNull(shareFeedbackDao().loadComment(share.getUuid(), reply.getUuid()), "A reply cannot outlive the comment it answers");
		assertNull(shareFeedbackDao().loadReaction(share.getUuid(), onRoot.getUuid()), "A reaction to a deleted comment goes with it");
		assertNotNull(shareFeedbackDao().loadComment(share.getUuid(), survivor.getUuid()), "An unrelated comment survives");
	}

	private void seedFeedback(Share share) {
		ShareComment comment = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "looks good");
		shareFeedbackDao().storeComment(comment);

		ShareAnnotation annotation = shareFeedbackDao().createAnnotation(share.getUuid(), asset().getUuid(), ShareAnnotationKind.SPATIAL, "Maria");
		annotation.setAreaX(0.1).setAreaY(0.2).setAreaWidth(0.3).setAreaHeight(0.4);
		shareFeedbackDao().storeAnnotation(annotation);

		ShareReaction reaction = shareFeedbackDao().createReaction(share.getUuid(), ShareReactionType.APPROVE, "Maria");
		reaction.setAssetUuid(asset().getUuid());
		shareFeedbackDao().storeReaction(reaction);
	}

	private int assetCounter = 0;

	private Asset dummyAsset(User user) {
		int i = assetCounter++;
		String base = SHA512SUM.toString().substring(0, 118);
		SHA512 sha = SHA512.fromString(base + String.format("%010x", System.nanoTime() % 0xFFFFFFFFFFL + i));
		Asset asset = assetDao().createAsset(user, sha, IMAGE_MIMETYPE, "share-cascade-" + i + ".png", DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(asset);
		return asset;
	}

	private String slug(String prefix) {
		return prefix + "_" + System.nanoTime();
	}
}
