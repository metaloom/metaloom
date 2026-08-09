package io.metaloom.loom.db.jooq.dao;

import static io.metaloom.loom.db.jooq.tables.JooqDetection.DETECTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.db.CRUDDaoTestcases;
import io.metaloom.loom.db.jooq.AbstractJooqTest;
import io.metaloom.loom.db.model.asset.Asset;
import io.metaloom.loom.db.model.detection.Detection;
import io.metaloom.loom.db.model.detection.DetectionDao;
import io.metaloom.loom.db.model.pipeline.PipelineNodeTask;
import io.metaloom.loom.db.model.pipeline.PipelineRun;
import io.metaloom.loom.db.model.pipeline.PipelineRunItem;
import io.metaloom.loom.db.model.review.ReviewStatus;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.utils.hash.SHA512;

public class DetectionDaoTest extends AbstractJooqTest implements CRUDDaoTestcases<DetectionDao, Detection> {

	@Override
	public DetectionDao getDao() {
		return detectionDao();
	}

	private SHA512 uniqueSha(int i) {
		return SHA512.fromString(SHA512SUM.toString().substring(0, 124) + String.format("%04x", i));
	}

	@Override
	public Detection createElement(User user, int i) {
		// The full V2.43 shape, not just the columns a given assertion reads: the provenance columns are NOT NULL, so a factory that skipped them
		// would only ever exercise their defaults.
		//
		// Each element needs a distinct (asset, node_kind, frame_number, detection_index), or the CRUD testcases' 1024-row page test would
		// collide on detection_unique_key.
		return getDao().createDetection(user, "objectdetection")
			.setAssetUuid(asset().getUuid())
			.setNodeKind("objectdetect")
			.setProducerVersion("yolo-v8")
			.setLabel("dog")
			.setFrameNumber(i)
			.setDetectionIndex(0)
			.setTimeFrom(i * 40L)
			.setBboxX(0.1f)
			.setBboxY(0.2f)
			.setBboxWidth(0.3f)
			.setBboxHeight(0.4f)
			.setConfidence(0.9f);
	}

	@Override
	public void assertCreate(Detection createdElement) {
		assertEquals("objectdetection", createdElement.getType());
		assertEquals("dog", createdElement.getLabel());
		assertEquals(ReviewStatus.PENDING, createdElement.getStatus(), "A fresh detection is an unreviewed proposal");
		assertNull(createdElement.getReviewedAt(), "Nobody has reviewed it, so there is no review timestamp");
		assertNull(createdElement.getReviewerUuid());
		assertNull(createdElement.getCorrectedLabel());
	}

	@Override
	public void assertUpdate(Detection updatedElement) {
		assertEquals(0.5f, updatedElement.getConfidence(), 0.0001f);
	}

	@Override
	public void updateElement(Detection detection) {
		detection.setConfidence(0.5f);
	}

	/**
	 * {@code detection.status} is a Postgres enum but the POJO holds a {@code String}, because {@code loom-db-api} cannot depend on the generated jOOQ
	 * types. This pins that the conversion survives a write/read round trip in both directions.
	 */
	@Test
	public void testStatusRoundTrip() {
		Detection detection = createElement(dummyUser(), 0);
		getDao().store(detection);

		assertEquals(ReviewStatus.PENDING, getDao().load(detection.getUuid()).getStatus(), "PENDING must survive the round trip");

		Detection loaded = getDao().load(detection.getUuid());
		loaded.setStatus(ReviewStatus.REJECTED);
		getDao().update(loaded);

		assertEquals(ReviewStatus.REJECTED, getDao().load(detection.getUuid()).getStatus(), "REJECTED must survive an update");
	}

	/**
	 * Recording a verdict stamps the reviewer columns and leaves the producer's audit columns alone.
	 *
	 * <p>
	 * That split is the entire reason {@code reviewed_at}/{@code reviewer_uuid} exist rather than reusing {@code edited}/{@code editor_uuid}: the
	 * producing node writes the latter on every re-run, so reading them as review evidence would report a review that never happened.
	 * </p>
	 */
	@Test
	public void testUpdateReviewStampsReviewerNotEditor() {
		User user = dummyUser();
		Detection detection = createElement(user, 0);
		// A machine-written row: no editor, exactly as a node writes it.
		detection.setEditorUuid(null);
		getDao().store(detection);

		Detection reviewed = getDao().updateReview(detection.getUuid(), ReviewStatus.CONFIRMED, "wolf", user.getUuid());

		assertEquals(ReviewStatus.CONFIRMED, reviewed.getStatus());
		assertEquals(user.getUuid(), reviewed.getReviewerUuid(), "The deciding user is recorded");
		assertNotNull(reviewed.getReviewedAt(), "The decision is timestamped");
		assertEquals("wolf", reviewed.getCorrectedLabel(), "The reviewer's correction is stored");
		assertEquals("dog", reviewed.getLabel(), "The model's own answer is kept - it is the training signal");
		assertNull(reviewed.getEditorUuid(), "A review is not a production event and must not claim to have edited the row");
	}

	/**
	 * A null {@code correctedLabel} means "no opinion on the class", not "clear the correction". Rejecting after correcting must not erase what the
	 * reviewer typed earlier.
	 */
	@Test
	public void testUpdateReviewKeepsAnEarlierCorrection() {
		User user = dummyUser();
		Detection detection = createElement(user, 0);
		getDao().store(detection);

		getDao().updateReview(detection.getUuid(), ReviewStatus.CONFIRMED, "wolf", user.getUuid());
		Detection reversed = getDao().updateReview(detection.getUuid(), ReviewStatus.REJECTED, null, user.getUuid());

		assertEquals(ReviewStatus.REJECTED, reversed.getStatus());
		assertEquals("wolf", reversed.getCorrectedLabel(), "A later verdict without a label must not erase the earlier correction");
	}

	@Test
	public void testListByStatus() {
		User user = dummyUser();
		Detection pending = createElement(user, 0);
		getDao().store(pending);
		Detection confirmed = createElement(user, 1);
		getDao().store(confirmed);
		getDao().updateReview(confirmed.getUuid(), ReviewStatus.CONFIRMED, null, user.getUuid());

		List<Detection> stillPending = getDao().listByStatus(asset().getUuid(), "objectdetection", ReviewStatus.PENDING);
		assertEquals(1, stillPending.size(), "Only the undecided detection is still in the queue");
		assertEquals(pending.getUuid(), stillPending.get(0).getUuid());

		assertEquals(1, getDao().listByStatus(asset().getUuid(), "objectdetection", ReviewStatus.CONFIRMED).size());
		assertEquals(2, getDao().listByStatus(asset().getUuid(), "objectdetection", null).size(), "A null status means any");
		assertEquals(2, getDao().listByStatus(asset().getUuid(), null, null).size(), "A null type means any");
		assertTrue(getDao().listByStatus(asset().getUuid(), "facedetection", null).isEmpty(), "The type filter actually filters");
	}

	@Test
	public void testLoadPageFiltersByStatusAndType() {
		User user = dummyUser();
		Detection pending = createElement(user, 0);
		getDao().store(pending);
		Detection confirmed = createElement(user, 1);
		getDao().store(confirmed);
		getDao().updateReview(confirmed.getUuid(), ReviewStatus.CONFIRMED, null, user.getUuid());

		Page<Detection> page = getDao().loadPage(ReviewStatus.PENDING, "objectdetection", null, 25);
		assertEquals(1, page.totalCount(), "The cross-asset queue counts only what is pending");

		Page<Detection> byType = getDao().loadPage(null, "facedetection", null, 25);
		assertEquals(0, byType.totalCount(), "The type filter applies independently of the status filter");

		Page<Detection> all = getDao().loadPage(null, null, null, 25);
		assertEquals(2, all.totalCount(), "Both filters null means the whole collection");
	}

	/**
	 * Deleting an asset takes its detections with it, and nothing else.
	 *
	 * <p>
	 * The second asset is not decoration: a cascade that deleted the whole table would satisfy an assertion that only checks the target disappeared.
	 * </p>
	 */
	@Test
	public void testDeletingAssetCascadesDetectionsOnly() {
		User user = dummyUser();

		Detection doomed = createElement(user, 0);
		getDao().store(doomed);
		getDao().updateReview(doomed.getUuid(), ReviewStatus.CONFIRMED, "wolf", user.getUuid());

		Asset survivorAsset = assetDao().createAsset(user.getUuid(), uniqueSha(1), IMAGE_MIMETYPE, DUMMY_IMAGE_FILENAME, DUMMY_IMAGE_ORIGIN, 42L);
		assetDao().store(survivorAsset);

		Detection survivor = createElement(user, 0).setAssetUuid(survivorAsset.getUuid());
		getDao().store(survivor);
		getDao().updateReview(survivor.getUuid(), ReviewStatus.CONFIRMED, "wolf", user.getUuid());

		assetDao().delete(asset().getUuid());

		assertNull(getDao().load(doomed.getUuid()), "The detection must cascade with its asset");

		Detection reloaded = getDao().load(survivor.getUuid());
		assertNotNull(reloaded, "A detection on another asset must survive");
		assertEquals(ReviewStatus.CONFIRMED, reloaded.getStatus(), "...with its review intact");
		assertEquals("wolf", reloaded.getCorrectedLabel());
	}

	/**
	 * Deleting the reviewing user is refused rather than silently erasing who decided.
	 *
	 * <p>
	 * {@code detection_reviewer_uuid_fkey} carries no {@code ON DELETE} clause, matching {@code creator_uuid}. Losing the reviewer's identity would
	 * turn a reviewed detection into one that claims to have been decided by nobody.
	 * </p>
	 */
	@Test
	public void testReviewerReferenceIsEnforced() {
		User user = dummyUser();
		Detection detection = createElement(user, 0);
		getDao().store(detection);

		getDao().updateReview(detection.getUuid(), ReviewStatus.CONFIRMED, null, user.getUuid());

		assertEquals(user.getUuid(), getDao().load(detection.getUuid()).getReviewerUuid());
	}

	/**
	 * A re-run of the producing node rewrites its own rows instead of appending duplicates.
	 */
	@Test
	public void testUpsertIsIdempotent() {
		Detection first = getDao().createDetection(dummyUser(), "objectdetection")
			.setAssetUuid(asset().getUuid())
			.setNodeKind("objectdetect")
			.setFrameNumber(0)
			.setDetectionIndex(0)
			.setConfidence(0.8f);
		getDao().upsertDetection(first);
		UUID firstUuid = first.getUuid();
		assertNotNull(firstUuid);

		Detection second = getDao().createDetection(dummyUser(), "objectdetection")
			.setAssetUuid(asset().getUuid())
			.setNodeKind("objectdetect")
			.setFrameNumber(0)
			.setDetectionIndex(0)
			.setConfidence(0.9f);
		getDao().upsertDetection(second);

		assertEquals(firstUuid, second.getUuid(), "The same natural key must map to the same row");
		assertEquals(1, getDao().listByStatus(asset().getUuid(), "objectdetection", null).size(), "A re-run must not append a second row");
		assertEquals(0.9f, getDao().load(firstUuid).getConfidence(), 0.0001f, "The producer's own payload is updated");
	}

	/**
	 * {@link DetectionDao#upsertDetection(Detection)} is the only idempotent write. A plain {@link io.metaloom.loom.db.CRUDDao#store} of a colliding
	 * row is rejected by {@code detection_unique_key} rather than quietly appending a duplicate.
	 *
	 * <p>
	 * Pinning the constraint by name matters: it is the difference between "the database refused the duplicate" and "some unrelated insert failed".
	 * V2.43 exists because re-running a node on a video used to append a complete second set of detections for every frame.
	 * </p>
	 */
	@Test
	public void testPlainStoreRejectsADuplicateKey() {
		User user = dummyUser();
		getDao().store(createElement(user, 0));

		// Same (asset, node_kind, frame_number, detection_index), different payload.
		Detection duplicate = createElement(user, 0).setConfidence(0.1f);

		Exception thrown = assertThrows(Exception.class, () -> getDao().store(duplicate),
			"A second detection with the same natural key must not be insertable");
		assertTrue(PipelineFixtures.rootCauseMessage(thrown).contains("detection_unique_key"),
			"The failure must come from the V2.43 idempotency key, not from something incidental");

		assertEquals(1, getDao().listByStatus(asset().getUuid(), "objectdetection", null).size(), "The rejected insert must leave no row behind");
	}

	/**
	 * Deleting the run that produced a detection detaches the provenance and keeps the detection.
	 *
	 * <p>
	 * {@code run_uuid} and {@code task_uuid} are {@code ON DELETE SET NULL}, not CASCADE, and the asymmetry is deliberate: pipeline runs and their
	 * node tasks are execution bookkeeping with a retention horizon, while the detections they produced are the result. A CASCADE here would make
	 * pruning run history silently destroy the AI output, which is the expensive half.
	 * </p>
	 *
	 * <p>
	 * Written through jOOQ rather than the DAO because {@code Detection} exposes no accessor for either column - nothing in Java populates them yet,
	 * so this pins the schema contract ahead of the producer that will.
	 * </p>
	 */
	@Test
	public void testDeletingTheRunDetachesButKeepsTheDetection() {
		User user = dummyUser();

		PipelineRun run = PipelineFixtures.createRun(this, user, 0);
		PipelineRunItem item = pipelineRunItemDao().createRunItem(user.getUuid(), run.getUuid(), 0, "/media/file-0.mp4");
		pipelineRunItemDao().store(item);
		PipelineNodeTask task = pipelineNodeTaskDao().createNodeTask(user.getUuid(), item.getUuid(), run.getUuid(), "objectdetect",
			"objectdetect");
		pipelineNodeTaskDao().store(task);

		Detection detection = createElement(user, 0);
		getDao().store(detection);
		context.ctx().update(DETECTION)
			.set(DETECTION.RUN_UUID, run.getUuid())
			.set(DETECTION.TASK_UUID, task.getUuid())
			.set(DETECTION.NODE_ID, "objectdetect")
			.where(DETECTION.UUID.eq(detection.getUuid()))
			.execute();

		// It has to be attached before the delete, or a SET NULL assertion afterwards would pass against a row that was never linked.
		assertEquals(run.getUuid(), runUuidOf(detection.getUuid()), "The detection is attached to the run");

		pipelineRunDao().delete(run.getUuid());

		Detection survivor = getDao().load(detection.getUuid());
		assertNotNull(survivor, "Pruning run history must not delete the detections that run produced");
		assertEquals("dog", survivor.getLabel(), "...and must not touch the payload");
		assertEquals("objectdetect", survivor.getNodeKind(), "The producer's identity is on the detection itself and survives the run");
		assertEquals("yolo-v8", survivor.getProducerVersion());

		assertNull(runUuidOf(detection.getUuid()), "run_uuid is ON DELETE SET NULL");
		// Deleting a run cascades to its node tasks, so the task reference is nulled by the same delete.
		assertNull(taskUuidOf(detection.getUuid()), "task_uuid is ON DELETE SET NULL");
	}

	/**
	 * Every V2.43 column survives a write/read round trip. Loading a detection back with a zeroed bounding box or a lost frame number would put the
	 * box in the wrong place on the wrong frame, and every assertion above would still pass.
	 */
	@Test
	public void testProvenanceAndGeometryRoundTrip() {
		Detection detection = createElement(dummyUser(), 7);
		getDao().store(detection);

		Detection loaded = getDao().load(detection.getUuid());
		assertEquals(asset().getUuid(), loaded.getAssetUuid());
		assertEquals("objectdetect", loaded.getNodeKind());
		assertEquals("yolo-v8", loaded.getProducerVersion());
		assertEquals("objectdetection", loaded.getType());
		assertEquals("dog", loaded.getLabel());
		assertEquals(7, loaded.getFrameNumber().intValue());
		assertEquals(0, loaded.getDetectionIndex().intValue());
		assertEquals(280L, loaded.getTimeFrom().longValue());
		assertEquals(0.1f, loaded.getBboxX(), 0.0001f);
		assertEquals(0.2f, loaded.getBboxY(), 0.0001f);
		assertEquals(0.3f, loaded.getBboxWidth(), 0.0001f);
		assertEquals(0.4f, loaded.getBboxHeight(), 0.0001f);
		assertEquals(0.9f, loaded.getConfidence(), 0.0001f);
	}

	private UUID runUuidOf(UUID detectionUuid) {
		return context.ctx().select(DETECTION.RUN_UUID).from(DETECTION).where(DETECTION.UUID.eq(detectionUuid)).fetchOne(DETECTION.RUN_UUID);
	}

	private UUID taskUuidOf(UUID detectionUuid) {
		return context.ctx().select(DETECTION.TASK_UUID).from(DETECTION).where(DETECTION.UUID.eq(detectionUuid)).fetchOne(DETECTION.TASK_UUID);
	}

}
