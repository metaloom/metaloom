package io.metaloom.loom.db.jooq.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.review.ReviewStatus;
import io.metaloom.loom.db.model.user.User;

/**
 * Pins the rule stated in {@code V2.81__detection_review_state.sql}: a node upsert must not clear a non-PENDING status.
 *
 * <p>
 * The upsert key is {@code (asset, node_kind, frame_number, detection_index)}, so a re-run overwrites row {@code #3} with whatever the node now calls
 * {@code #3}. Left unguarded that silently replaces a reviewed answer with an unreviewed one - the class of bug {@code V2.43}'s upsert key was
 * introduced to fix, reappearing one level up.
 * </p>
 *
 * <p>
 * The guard is conditional on {@code producer_version}, so both halves need pinning: the verdict survives a re-run of the same model, and it is
 * retired by a different one.
 * </p>
 */
public class DetectionUpsertReviewTest extends AbstractJooqTest {

	private static final String NODE_KIND = "objectdetect";

	private Detection proposal(String producerVersion, float confidence) {
		return detectionDao().createDetection(dummyUser(), "objectdetection")
			.setAssetUuid(asset().getUuid())
			.setNodeKind(NODE_KIND)
			.setProducerVersion(producerVersion)
			.setLabel("dog")
			.setFrameNumber(0)
			.setDetectionIndex(0)
			.setConfidence(confidence);
	}

	/**
	 * The same model running again keeps the human's answer. Within one {@code producer_version} the ordinal is stable, so {@code #0} is still the box
	 * the reviewer looked at, and their verdict still applies to it.
	 */
	@Test
	public void testReRunDoesNotClearAConfirmedStatus() {
		User user = dummyUser();

		Detection proposed = proposal("yolov8n-1.0", 0.80f);
		detectionDao().upsertDetection(proposed);

		detectionDao().updateReview(proposed.getUuid(), ReviewStatus.CONFIRMED, "wolf", user.getUuid());

		// The node runs again over the same asset and re-proposes the same box as a fresh, unreviewed detection.
		Detection reproposed = proposal("yolov8n-1.0", 0.83f);
		detectionDao().upsertDetection(reproposed);

		assertEquals(proposed.getUuid(), reproposed.getUuid(), "The same natural key must map to the same row");

		Detection reloaded = detectionDao().load(proposed.getUuid());
		assertEquals(ReviewStatus.CONFIRMED, reloaded.getStatus(), "A re-run must not reset the reviewer's verdict");
		assertEquals("wolf", reloaded.getCorrectedLabel(), "A re-run must not drop the reviewer's correction");
		assertEquals(user.getUuid(), reloaded.getReviewerUuid(), "A re-run must not drop who decided");
		assertNotNull(reloaded.getReviewedAt(), "A re-run must not drop when they decided");
		assertEquals(0.83f, reloaded.getConfidence(), 0.0001f, "The producer's own payload is still updated");
	}

	/** REJECTED is a decision too: a re-run must not quietly return a known false positive to the queue. */
	@Test
	public void testReRunDoesNotClearARejectedStatus() {
		User user = dummyUser();

		Detection proposed = proposal("yolov8n-1.0", 0.80f);
		detectionDao().upsertDetection(proposed);
		detectionDao().updateReview(proposed.getUuid(), ReviewStatus.REJECTED, null, user.getUuid());

		detectionDao().upsertDetection(proposal("yolov8n-1.0", 0.81f));

		assertEquals(ReviewStatus.REJECTED, detectionDao().load(proposed.getUuid()).getStatus(),
			"A rejected false positive must not come back as pending");
	}

	/**
	 * A different model retires the verdict.
	 *
	 * <p>
	 * {@code detection_index} is an ordinal, not an identity, and it is not stable across model versions: after an upgrade, {@code #0} may well be a
	 * different object. Carrying the verdict forward would attribute a human decision to a box they never saw, so the review is reset rather than
	 * inherited.
	 * </p>
	 */
	@Test
	public void testProducerVersionChangeRetiresTheReview() {
		User user = dummyUser();

		Detection proposed = proposal("yolov8n-1.0", 0.80f);
		detectionDao().upsertDetection(proposed);
		detectionDao().updateReview(proposed.getUuid(), ReviewStatus.CONFIRMED, "wolf", user.getUuid());

		// The model is upgraded and the node re-runs.
		Detection reproposed = proposal("yolov8n-2.0", 0.91f);
		detectionDao().upsertDetection(reproposed);

		Detection reloaded = detectionDao().load(proposed.getUuid());
		assertEquals(ReviewStatus.PENDING, reloaded.getStatus(), "A model change must return the row to the review queue");
		assertNull(reloaded.getReviewedAt(), "The retired review keeps no timestamp");
		assertNull(reloaded.getReviewerUuid(), "The retired review keeps no reviewer");
		assertNull(reloaded.getCorrectedLabel(), "The retired review keeps no correction");
		assertEquals("yolov8n-2.0", reloaded.getProducerVersion(), "The new producer version is recorded");
	}

	/**
	 * The guard must not interfere with the ordinary case: an unreviewed row stays PENDING through a re-run, whichever version wrote it.
	 */
	@Test
	public void testAnUnreviewedDetectionStaysPending() {
		Detection proposed = proposal("yolov8n-1.0", 0.80f);
		detectionDao().upsertDetection(proposed);

		detectionDao().upsertDetection(proposal("yolov8n-2.0", 0.85f));

		Detection reloaded = detectionDao().load(proposed.getUuid());
		assertEquals(ReviewStatus.PENDING, reloaded.getStatus());
		assertEquals(0.85f, reloaded.getConfidence(), 0.0001f);
	}

}
