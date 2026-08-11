package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.share.Share;
import io.metaloom.loom.db.model.share.ShareAnnotation;
import io.metaloom.loom.db.model.share.ShareAnnotationKind;
import io.metaloom.loom.db.model.share.ShareComment;
import io.metaloom.loom.db.model.share.ShareReaction;
import io.metaloom.loom.db.model.share.ShareReactionType;
import io.metaloom.loom.db.model.user.User;

/**
 * {@code ShareFeedbackDao} is a facade over three tables rather than a {@code CRUDDao}, so it cannot inherit {@code CRUDDaoTestcases} and is covered
 * directly here.
 *
 * <p>
 * The theme running through this class is <b>share scoping</b>. Guest endpoints address rows by uuid on a request authenticated by nothing but a
 * slug, so "load this comment" must always mean "...and only if it is on my share". Every loader is therefore tested twice: once with the right
 * share, once with a different one.
 * </p>
 */
public class ShareFeedbackDaoTest extends AbstractJooqTest {

	@Test
	public void testCommentRoundTrip() {
		Share share = share();

		ShareComment comment = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "the second cut is too long");
		shareFeedbackDao().storeComment(comment);
		assertNotNull(comment.getUuid(), "Storing assigns the generated uuid back onto the element");

		ShareComment loaded = shareFeedbackDao().loadComment(share.getUuid(), comment.getUuid());
		assertNotNull(loaded);
		assertEquals("the second cut is too long", loaded.getText());
		assertEquals("Maria", loaded.getAuthorName());
		assertEquals(asset().getUuid(), loaded.getAssetUuid());
		assertNotNull(loaded.getCreated());

		loaded.setText("the second cut is much too long");
		shareFeedbackDao().updateComment(loaded);
		assertEquals("the second cut is much too long", shareFeedbackDao().loadComment(share.getUuid(), comment.getUuid()).getText());

		shareFeedbackDao().deleteComment(comment.getUuid());
		assertNull(shareFeedbackDao().loadComment(share.getUuid(), comment.getUuid()));
	}

	/**
	 * A comment with no asset belongs to the shared collection as a whole.
	 */
	@Test
	public void testCollectionLevelComment() {
		Share share = share();
		ShareComment comment = shareFeedbackDao().createComment(share.getUuid(), null, "Maria", "nice set");
		shareFeedbackDao().storeComment(comment);

		ShareComment loaded = shareFeedbackDao().loadComment(share.getUuid(), comment.getUuid());
		assertNull(loaded.getAssetUuid(), "A collection-level comment names no asset");

		assertTrue(shareFeedbackDao().listComments(share.getUuid(), null).stream().anyMatch(c -> c.getUuid().equals(comment.getUuid())),
			"The unfiltered listing includes collection-level comments");
		assertTrue(shareFeedbackDao().listComments(share.getUuid(), asset().getUuid()).stream().noneMatch(c -> c.getUuid().equals(comment.getUuid())),
			"Narrowing to an asset excludes them");
	}

	/**
	 * The security property the guest endpoints depend on: one link cannot address another link's rows.
	 */
	@Test
	public void testLoadersAreScopedToTheirShare() {
		Share mine = share();
		Share theirs = share();

		ShareComment comment = shareFeedbackDao().createComment(mine.getUuid(), asset().getUuid(), "Maria", "mine");
		shareFeedbackDao().storeComment(comment);

		ShareAnnotation annotation = shareFeedbackDao().createAnnotation(mine.getUuid(), asset().getUuid(), ShareAnnotationKind.TEMPORAL, "Maria");
		annotation.setTimeFrom(3.0);
		shareFeedbackDao().storeAnnotation(annotation);

		ShareReaction reaction = shareFeedbackDao().createReaction(mine.getUuid(), ShareReactionType.APPROVE, "Maria");
		reaction.setAssetUuid(asset().getUuid());
		shareFeedbackDao().storeReaction(reaction);

		assertNotNull(shareFeedbackDao().loadComment(mine.getUuid(), comment.getUuid()));
		assertNotNull(shareFeedbackDao().loadAnnotation(mine.getUuid(), annotation.getUuid()));
		assertNotNull(shareFeedbackDao().loadReaction(mine.getUuid(), reaction.getUuid()));

		assertNull(shareFeedbackDao().loadComment(theirs.getUuid(), comment.getUuid()),
			"Holding a different link must not resolve this comment");
		assertNull(shareFeedbackDao().loadAnnotation(theirs.getUuid(), annotation.getUuid()),
			"Holding a different link must not resolve this annotation");
		assertNull(shareFeedbackDao().loadReaction(theirs.getUuid(), reaction.getUuid()),
			"Holding a different link must not resolve this reaction");

		assertTrue(shareFeedbackDao().listComments(theirs.getUuid(), null).isEmpty(), "...nor list it");
	}

	@Test
	public void testAnnotationGeometryRoundTrip() {
		Share share = share();

		ShareAnnotation spatial = shareFeedbackDao().createAnnotation(share.getUuid(), asset().getUuid(), ShareAnnotationKind.SPATIAL, "Maria");
		spatial.setAreaX(0.25).setAreaY(0.5).setAreaWidth(0.125).setAreaHeight(0.25).setText("this logo");
		shareFeedbackDao().storeAnnotation(spatial);

		ShareAnnotation loaded = shareFeedbackDao().loadAnnotation(share.getUuid(), spatial.getUuid());
		assertEquals(ShareAnnotationKind.SPATIAL, loaded.kind());
		assertEquals(0.25, loaded.getAreaX());
		assertEquals(0.5, loaded.getAreaY());
		assertEquals(0.125, loaded.getAreaWidth());
		assertEquals(0.25, loaded.getAreaHeight());
		assertNull(loaded.getTimeFrom(), "A purely spatial mark carries no timecode");

		ShareAnnotation both = shareFeedbackDao().createAnnotation(share.getUuid(), asset().getUuid(), ShareAnnotationKind.SPATIOTEMPORAL, "Maria");
		both.setTimeFrom(14.25).setTimeTo(19.5).setAreaX(0.1).setAreaY(0.1).setAreaWidth(0.2).setAreaHeight(0.2);
		shareFeedbackDao().storeAnnotation(both);

		ShareAnnotation loadedBoth = shareFeedbackDao().loadAnnotation(share.getUuid(), both.getUuid());
		// Sub-second precision is the entire reason this column is a float rather than the integer seconds the
		// internal annotation table uses.
		assertEquals(14.25, loadedBoth.getTimeFrom());
		assertEquals(19.5, loadedBoth.getTimeTo());
	}

	/**
	 * The database refuses a mark that does not carry the geometry its kind claims. Rendering nothing would look like a UI bug.
	 */
	@Test
	public void testAnnotationGeometryIsEnforced() {
		Share share = share();
		ShareAnnotation bogus = shareFeedbackDao().createAnnotation(share.getUuid(), asset().getUuid(), ShareAnnotationKind.SPATIAL, "Maria");
		// No area set at all.
		assertThrows(Exception.class, () -> shareFeedbackDao().storeAnnotation(bogus),
			"A SPATIAL annotation with no region must be rejected by share_annotation_geometry_check");

		ShareAnnotation outOfFrame = shareFeedbackDao().createAnnotation(share.getUuid(), asset().getUuid(), ShareAnnotationKind.SPATIAL, "Maria");
		outOfFrame.setAreaX(1.5).setAreaY(0.1).setAreaWidth(0.2).setAreaHeight(0.2);
		assertThrows(Exception.class, () -> shareFeedbackDao().storeAnnotation(outOfFrame),
			"Coordinates are normalised 0..1; a box outside the frame must be rejected");
	}

	/**
	 * Annotations list in media order, not in the order they were written - the timeline under the player draws them along the clip.
	 */
	@Test
	public void testAnnotationsListInTimecodeOrder() {
		Share share = share();
		storeTemporal(share, 30.0);
		storeTemporal(share, 5.0);
		storeTemporal(share, 12.5);

		List<ShareAnnotation> annotations = shareFeedbackDao().listAnnotations(share.getUuid(), asset().getUuid());
		assertEquals(3, annotations.size());
		assertEquals(5.0, annotations.get(0).getTimeFrom());
		assertEquals(12.5, annotations.get(1).getTimeFrom());
		assertEquals(30.0, annotations.get(2).getTimeFrom());
	}

	/**
	 * A double-clicked thumbs-up is a normal thing for a person to do, not a 500.
	 */
	@Test
	public void testReactionIsIdempotent() {
		Share share = share();

		ShareReaction first = shareFeedbackDao().createReaction(share.getUuid(), ShareReactionType.APPROVE, "Maria");
		first.setAssetUuid(asset().getUuid());
		shareFeedbackDao().storeReaction(first);

		ShareReaction again = shareFeedbackDao().createReaction(share.getUuid(), ShareReactionType.APPROVE, "Maria");
		again.setAssetUuid(asset().getUuid());
		shareFeedbackDao().storeReaction(again);

		assertEquals(1, shareFeedbackDao().countReactions(share.getUuid()), "The same reaction twice is one row");

		// A different type is a different opinion and gets its own row.
		ShareReaction other = shareFeedbackDao().createReaction(share.getUuid(), ShareReactionType.QUESTION, "Maria");
		other.setAssetUuid(asset().getUuid());
		shareFeedbackDao().storeReaction(other);
		assertEquals(2, shareFeedbackDao().countReactions(share.getUuid()));
	}

	/**
	 * Two links may hold the same opinion about the same asset - the uniqueness key is per share, not global.
	 */
	@Test
	public void testTwoSharesMayReactToTheSameAsset() {
		Share first = share();
		Share second = share();

		ShareReaction a = shareFeedbackDao().createReaction(first.getUuid(), ShareReactionType.APPROVE, "Maria");
		a.setAssetUuid(asset().getUuid());
		shareFeedbackDao().storeReaction(a);

		ShareReaction b = shareFeedbackDao().createReaction(second.getUuid(), ShareReactionType.APPROVE, "Jon");
		b.setAssetUuid(asset().getUuid());
		shareFeedbackDao().storeReaction(b);

		assertEquals(1, shareFeedbackDao().countReactions(first.getUuid()));
		assertEquals(1, shareFeedbackDao().countReactions(second.getUuid()));
	}

	@Test
	public void testCommentsListOldestFirst() {
		Share share = share();
		ShareComment first = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "first");
		shareFeedbackDao().storeComment(first);
		ShareComment second = shareFeedbackDao().createComment(share.getUuid(), asset().getUuid(), "Maria", "second");
		shareFeedbackDao().storeComment(second);

		List<ShareComment> comments = shareFeedbackDao().listComments(share.getUuid(), asset().getUuid());
		assertEquals(2, comments.size());
		assertEquals("first", comments.get(0).getText(), "A reply must never render above the comment it answers");
		assertEquals("second", comments.get(1).getText());
	}

	private void storeTemporal(Share share, double at) {
		ShareAnnotation annotation = shareFeedbackDao().createAnnotation(share.getUuid(), asset().getUuid(), ShareAnnotationKind.TEMPORAL, "Maria");
		annotation.setTimeFrom(at);
		shareFeedbackDao().storeAnnotation(annotation);
	}

	private Share share() {
		User user = dummyUser();
		Share share = shareDao().createAssetShare(user.getUuid(), asset().getUuid(), "feedback_" + System.nanoTime());
		share.setAllowComments(true).setAllowReactions(true).setAllowAnnotations(true);
		shareDao().store(share);
		return share;
	}
}
