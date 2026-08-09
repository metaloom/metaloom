package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.db.dagger.DaoCollection;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.review.ReviewStatus;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkReviewRequest;
import io.metaloom.loom.rest.model.detection.DetectionConfirmRequest;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionListResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.detection.DetectionReviewItem;
import io.metaloom.loom.rest.model.detection.DetectionUpdateRequest;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

public class DetectionEndpointTest extends AbstractCRUDEndpointTest {

	private static int sha512Counter = 0;

	private SHA512 nextSHA512() {
		sha512Counter++;
		String hex = String.format("%0128x", sha512Counter);
		return SHA512.fromString(hex);
	}

	private AssetResponse createTestAsset(LoomHttpClient client) throws LoomClientException {
		AssetCreateRequest request = new AssetCreateRequest();
		FileInfo fileInfo = new FileInfo();
		fileInfo.setMimeType(IMAGE_MIMETYPE);
		fileInfo.setFilename("detection-test.png");
		fileInfo.setSize(1024L);
		fileInfo.setOrigin(INITIAL_ORIGIN);
		request.setFile(fileInfo);
		HashInfo hashes = new HashInfo();
		hashes.setSHA512(nextSHA512());
		request.setHashes(hashes);
		return client.createAsset(request).sync().body();
	}

	private DetectionResponse createTestDetection(LoomHttpClient client, UUID assetUuid) throws LoomClientException {
		return createTestDetection(client, assetUuid, 0);
	}

	/**
	 * @param frameNumber
	 *            distinct per detection on the same asset: {@code detection_unique_key} is
	 *            {@code (asset_uuid, node_kind, frame_number, detection_index)}, and a manually created detection leaves node_kind at "manual" and
	 *            detection_index at 0, so two of them on one asset differ only here.
	 */
	private DetectionResponse createTestDetection(LoomHttpClient client, UUID assetUuid, int frameNumber) throws LoomClientException {
		DetectionCreateRequest request = new DetectionCreateRequest();
		request.setType("facedetection");
		request.setFrameNumber(frameNumber);
		request.setBboxX(0.1f);
		request.setBboxY(0.2f);
		request.setBboxWidth(0.3f);
		request.setBboxHeight(0.4f);
		request.setConfidence(0.95f);
		request.setMeta(new JsonObject().put("gender", "male").put("age", 30));
		return client.createAssetDetection(assetUuid, request).sync().body();
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		AssetResponse asset = createTestAsset(client);
		DetectionResponse created = createTestDetection(client, asset.getUuid());
		DetectionResponse loaded = client.loadAssetDetection(asset.getUuid(), created.getUuid()).sync().body();
		assertNotNull(loaded);
		assertEquals(created.getUuid(), loaded.getUuid());
		assertEquals("facedetection", loaded.getType());
		assertEquals(0, loaded.getFrameNumber());
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		AssetResponse asset = createTestAsset(client);
		DetectionCreateRequest request = new DetectionCreateRequest();
		request.setType("objectdetection");
		request.setFrameNumber(30);
		request.setBboxX(0.5f);
		request.setBboxY(0.6f);
		request.setBboxWidth(0.2f);
		request.setBboxHeight(0.3f);
		request.setConfidence(0.88f);
		request.setMeta(new JsonObject().put("label", "car"));
		DetectionResponse response = client.createAssetDetection(asset.getUuid(), request).sync().body();
		assertNotNull(response);
		assertNotNull(response.getUuid());
		assertEquals("objectdetection", response.getType());
		assertEquals(30, response.getFrameNumber());
		assertEquals(0.88f, response.getConfidence(), 0.01f);

		// Verify it can be loaded
		DetectionResponse loaded = client.loadAssetDetection(asset.getUuid(), response.getUuid()).sync().body();
		assertEquals(response.getUuid(), loaded.getUuid());
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		AssetResponse asset = createTestAsset(client);
		DetectionResponse created = createTestDetection(client, asset.getUuid());
		client.deleteAssetDetection(asset.getUuid(), created.getUuid()).sync().body();
		expect(404, "Not Found", client.loadAssetDetection(asset.getUuid(), created.getUuid()));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		AssetResponse asset = createTestAsset(client);
		DetectionResponse created = createTestDetection(client, asset.getUuid());
		DetectionUpdateRequest update = new DetectionUpdateRequest();
		update.setType("objectdetection");
		update.setConfidence(0.99f);
		update.setFrameNumber(60);
		DetectionResponse updated = client.updateAssetDetection(asset.getUuid(), created.getUuid(), update).sync().body();
		assertNotNull(updated);
		assertEquals("objectdetection", updated.getType());
		assertEquals(0.99f, updated.getConfidence(), 0.01f);
		assertEquals(60, updated.getFrameNumber());
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		AssetResponse asset = createTestAsset(client);
		for (int i = 0; i < 30; i++) {
			DetectionCreateRequest request = new DetectionCreateRequest();
			request.setType("facedetection");
			request.setFrameNumber(i);
			request.setConfidence(0.9f);
			client.createAssetDetection(asset.getUuid(), request).sync().body();
		}
		DetectionListResponse list = client.listAssetDetections(asset.getUuid()).sync().body();
		assertNotNull(list);
		assertEquals(25, list.getMetainfo().getPerPage());
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		DetectionCreateRequest request = new DetectionCreateRequest();
		request.setType("facedetection");
		request.setFrameNumber(0);
		request.setConfidence(0.95f);
		return client.createAssetDetection(ASSET_UUID, request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadAssetDetection(ASSET_UUID, UUID.randomUUID());
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listAssetDetections(ASSET_UUID);
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteAssetDetection(ASSET_UUID, UUID.randomUUID());
	}

	@org.junit.jupiter.api.Test
	public void testBulkCreate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			DetectionBulkCreateRequest bulkRequest = new DetectionBulkCreateRequest();
			List<DetectionCreateRequest> detections = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				DetectionCreateRequest req = new DetectionCreateRequest();
				req.setType("objectdetection");
				req.setFrameNumber(i * 30);
				req.setBboxX(0.1f * i);
				req.setBboxY(0.2f);
				req.setBboxWidth(0.15f);
				req.setBboxHeight(0.2f);
				req.setConfidence(0.85f + i * 0.02f);
				req.setMeta(new JsonObject().put("label", "object_" + i));
				detections.add(req);
			}
			bulkRequest.setDetections(detections);
			DetectionBulkResponse response = client.bulkCreateAssetDetections(asset.getUuid(), bulkRequest).sync().body();
			assertNotNull(response);
			assertEquals(5, response.getCreated());
			assertEquals(5, response.getTotal());
			assertEquals(0, response.getFailed());
			assertEquals(5, response.getDetections().size());
		}
	}

	/**
	 * The class label has to survive the round trip.
	 *
	 * <p>
	 * {@code detection.label} is an indexed column added for object detection — "Detected class for
	 * object detection, e.g. dog" — and the create request has always carried it. The response did
	 * not, so a label could be written and never read back: an {@code objectdetect} row was findable
	 * by geometry and by nothing else.
	 * </p>
	 */
	@org.junit.jupiter.api.Test
	public void testLabelIsReadBack() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			DetectionCreateRequest request = new DetectionCreateRequest();
			request.setType("objectdetection");
			request.setLabel("dog");
			request.setFrameNumber(0);
			request.setBboxX(0.1f);
			request.setBboxY(0.2f);
			request.setBboxWidth(0.3f);
			request.setBboxHeight(0.4f);
			request.setConfidence(0.9f);

			DetectionResponse created = client.createAssetDetection(asset.getUuid(), request).sync().body();
			assertEquals("dog", created.getLabel(), "the create response must echo the label");

			DetectionResponse loaded = client.loadAssetDetection(asset.getUuid(), created.getUuid()).sync().body();
			assertEquals("dog", loaded.getLabel(), "the label must survive a read");

			DetectionResponse listed = client.listAssetDetections(asset.getUuid()).sync().body().getData().stream()
				.filter(d -> created.getUuid().equals(d.getUuid()))
				.findFirst()
				.orElseThrow();
			assertEquals("dog", listed.getLabel(), "the label must survive a list");
		}
	}

	/**
	 * A face detection has no class, and must not invent one.
	 */
	@org.junit.jupiter.api.Test
	public void testAnUnlabelledDetectionReadsBackNull() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			DetectionCreateRequest request = new DetectionCreateRequest();
			request.setType("facedetection");
			request.setFrameNumber(0);
			request.setBboxX(0.1f);
			request.setBboxY(0.2f);
			request.setBboxWidth(0.3f);
			request.setBboxHeight(0.4f);
			request.setConfidence(0.9f);

			DetectionResponse created = client.createAssetDetection(asset.getUuid(), request).sync().body();
			assertNull(client.loadAssetDetection(asset.getUuid(), created.getUuid()).sync().body().getLabel());
		}
	}

	// --- Review ---

	/**
	 * A fresh detection is an unreviewed proposal, and confirming it records who said so.
	 */
	@Test
	public void testConfirmDetection() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			DetectionResponse created = createTestDetection(client, asset.getUuid());
			assertEquals(ReviewStatus.PENDING, created.getReviewStatus(), "A new detection starts unreviewed");
			assertNull(created.getReviewedAt());

			DetectionResponse confirmed = client.confirmAssetDetection(asset.getUuid(), created.getUuid(), null).sync().body();
			assertEquals(ReviewStatus.CONFIRMED, confirmed.getReviewStatus());
			assertNotNull(confirmed.getReviewedAt(), "The verdict is timestamped");
			assertNull(confirmed.getCorrectedLabel(), "Confirming without a body corrects nothing");

			DetectionResponse reloaded = client.loadAssetDetection(asset.getUuid(), created.getUuid()).sync().body();
			assertEquals(ReviewStatus.CONFIRMED, reloaded.getReviewStatus(), "The verdict survives a read");
		}
	}

	/**
	 * The third answer: the box was right but the class was wrong. That is a confirmation carrying a correction, and it must not overwrite what the
	 * model said.
	 */
	@Test
	public void testConfirmWithCorrectedLabelKeepsTheOriginal() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			DetectionCreateRequest request = new DetectionCreateRequest();
			request.setType("objectdetection");
			request.setLabel("dog");
			request.setFrameNumber(0);
			request.setConfidence(0.9f);
			DetectionResponse created = client.createAssetDetection(asset.getUuid(), request).sync().body();

			DetectionConfirmRequest confirm = new DetectionConfirmRequest().setCorrectedLabel("wolf");
			DetectionResponse confirmed = client.confirmAssetDetection(asset.getUuid(), created.getUuid(), confirm).sync().body();

			assertEquals(ReviewStatus.CONFIRMED, confirmed.getReviewStatus());
			assertEquals("wolf", confirmed.getCorrectedLabel(), "The reviewer's class is recorded");
			assertEquals("dog", confirmed.getLabel(), "The model's own answer is kept - it is the training signal");
		}
	}

	/**
	 * Rejecting keeps the row. It is the record that the producer was wrong here.
	 */
	@Test
	public void testRejectDetectionKeepsTheRow() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			DetectionResponse created = createTestDetection(client, asset.getUuid());

			DetectionResponse rejected = client.rejectAssetDetection(asset.getUuid(), created.getUuid()).sync().body();
			assertEquals(ReviewStatus.REJECTED, rejected.getReviewStatus());

			assertNotNull(client.loadAssetDetection(asset.getUuid(), created.getUuid()).sync().body(),
				"A rejected detection is kept, not deleted");
		}
	}

	@Test
	public void testReviewOfAnUnknownDetectionIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			expect(404, "Not Found", client.confirmAssetDetection(asset.getUuid(), UUID.randomUUID(), null));
			expect(404, "Not Found", client.rejectAssetDetection(asset.getUuid(), UUID.randomUUID()));
		}
	}

	/**
	 * A detection that exists but belongs to another asset is answered as missing rather than as forbidden: the asset/detection pairing is part of the
	 * address, and confirming that the uuid exists elsewhere would leak it.
	 */
	@Test
	public void testReviewingAcrossAssetsIs404() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse assetA = createTestAsset(client);
			AssetResponse assetB = createTestAsset(client);
			DetectionResponse onA = createTestDetection(client, assetA.getUuid());

			expect(404, "Not Found", client.confirmAssetDetection(assetB.getUuid(), onA.getUuid(), null));

			assertEquals(ReviewStatus.PENDING, client.loadAssetDetection(assetA.getUuid(), onA.getUuid()).sync().body().getReviewStatus(),
				"The failed cross-asset review must not have decided anything");
		}
	}

	/**
	 * The bulk route reports {@code {total, created, failed}} and applies the good items even when one is bad.
	 *
	 * <p>
	 * Losing twenty good decisions because the twenty-first named a stale uuid would be worse than reporting the one that did not apply.
	 * </p>
	 */
	@Test
	public void testBulkReviewPartialFailure() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			DetectionResponse first = createTestDetection(client, asset.getUuid(), 0);
			DetectionResponse second = createTestDetection(client, asset.getUuid(), 1);

			DetectionBulkReviewRequest request = new DetectionBulkReviewRequest();
			request.add(new DetectionReviewItem().setUuid(first.getUuid().toString()).setStatus(ReviewStatus.CONFIRMED).setCorrectedLabel("wolf"));
			request.add(new DetectionReviewItem().setUuid(second.getUuid().toString()).setStatus(ReviewStatus.REJECTED));
			// Three ways to be a bad item: unknown uuid, unparseable uuid, and a verdict that is not one.
			request.add(new DetectionReviewItem().setUuid(UUID.randomUUID().toString()).setStatus(ReviewStatus.CONFIRMED));
			request.add(new DetectionReviewItem().setUuid("not-a-uuid").setStatus(ReviewStatus.CONFIRMED));
			request.add(new DetectionReviewItem().setUuid(first.getUuid().toString()).setStatus("MAYBE"));

			DetectionBulkResponse response = client.bulkReviewAssetDetections(asset.getUuid(), request).sync().body();
			assertEquals(5, response.getTotal());
			assertEquals(2, response.getCreated(), "Both applicable verdicts are recorded");
			assertEquals(3, response.getFailed(), "Each inapplicable item is reported rather than failing the batch");

			assertEquals(ReviewStatus.CONFIRMED, client.loadAssetDetection(asset.getUuid(), first.getUuid()).sync().body().getReviewStatus());
			assertEquals(ReviewStatus.REJECTED, client.loadAssetDetection(asset.getUuid(), second.getUuid()).sync().body().getReviewStatus());
		}
	}

	/**
	 * The cross-asset review queue, and that its filters actually filter.
	 */
	@Test
	public void testListDetectionsByStatus() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			AssetResponse asset = createTestAsset(client);
			DetectionResponse pending = createTestDetection(client, asset.getUuid(), 0);
			DetectionResponse decided = createTestDetection(client, asset.getUuid(), 1);
			client.confirmAssetDetection(asset.getUuid(), decided.getUuid(), null).sync().body();

			List<UUID> confirmedUuids = client.listDetections(ReviewStatus.CONFIRMED, "facedetection").sync().body()
				.getData().stream().map(DetectionResponse::getUuid).toList();
			assertTrue(confirmedUuids.contains(decided.getUuid()), "The confirmed detection is in the CONFIRMED queue");
			assertFalse(confirmedUuids.contains(pending.getUuid()), "The pending one is not");

			List<UUID> pendingUuids = client.listDetections(ReviewStatus.PENDING, "facedetection").sync().body()
				.getData().stream().map(DetectionResponse::getUuid).toList();
			assertTrue(pendingUuids.contains(pending.getUuid()), "The pending detection is in the PENDING queue");
			assertFalse(pendingUuids.contains(decided.getUuid()), "The confirmed one is not");
		}
	}

	@Test
	public void testAnInvalidStatusIs400() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(400, "Bad Request", client.listDetections("MAYBE", null));
		}
	}

	/**
	 * Reviewing needs {@code UPDATE_DETECTION}, and reading a detection is not enough to decide about it.
	 *
	 * <p>
	 * {@code AbstractCRUDEndpointTest} deliberately has no generic update-permission case, which is why {@code UPDATE_DETECTION} carried
	 * {@code test:none}. The permissions are granted through a group and a role rather than directly, because {@code user_permission} allows a single
	 * direct grant per user.
	 * </p>
	 */
	@Test
	public void testReviewIsForbiddenWithoutUpdatePermission() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			AssetResponse asset;
			DetectionResponse detection;
			try (LoomHttpClient adminClient = loom.httpClient()) {
				loginAdmin(adminClient);
				asset = createTestAsset(adminClient);
				detection = createTestDetection(adminClient, asset.getUuid());
			}

			// READ but not UPDATE: the reviewer can see the detection and still may not decide about it.
			loginJoeDoeWith(client, Permission.READ_DETECTION);

			assertNotNull(client.loadAssetDetection(asset.getUuid(), detection.getUuid()).sync().body(), "READ_DETECTION is enough to look");

			expect(403, "Forbidden", client.confirmAssetDetection(asset.getUuid(), detection.getUuid(), null));
			expect(403, "Forbidden", client.rejectAssetDetection(asset.getUuid(), detection.getUuid()));
			expect(403, "Forbidden", client.bulkReviewAssetDetections(asset.getUuid(), new DetectionBulkReviewRequest()));

			assertEquals(ReviewStatus.PENDING, client.loadAssetDetection(asset.getUuid(), detection.getUuid()).sync().body().getReviewStatus(),
				"A forbidden review must not have decided anything");
		}
	}

	/**
	 * The same user with the same setup, plus {@code UPDATE_DETECTION}, can review - which is what makes the 403 above specifically about that
	 * permission rather than about something incidental to the fixture.
	 *
	 * <p>
	 * A separate test rather than a second half of the previous one, because {@link io.metaloom.loom.auth.PermissionCache} is keyed by user with no
	 * expiry: a grant written straight to the DAO after a check has already run is invisible until something calls {@code invalidateAll()}, which only
	 * the role endpoint does. A fresh test gets a cold cache.
	 * </p>
	 */
	@Test
	public void testReviewIsAllowedWithUpdatePermission() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			AssetResponse asset;
			DetectionResponse detection;
			try (LoomHttpClient adminClient = loom.httpClient()) {
				loginAdmin(adminClient);
				asset = createTestAsset(adminClient);
				detection = createTestDetection(adminClient, asset.getUuid());
			}

			loginJoeDoeWith(client, Permission.READ_DETECTION, Permission.UPDATE_DETECTION);

			assertEquals(ReviewStatus.CONFIRMED,
				client.confirmAssetDetection(asset.getUuid(), detection.getUuid(), null).sync().body().getReviewStatus());
		}
	}

	/**
	 * Grant the joedoe fixture user the given permissions and log the client in as them.
	 *
	 * <p>
	 * Through a group and a role rather than directly: {@code user_permission} is keyed by user alone, so a user can hold exactly one direct grant.
	 * </p>
	 */
	private void loginJoeDoeWith(LoomHttpClient client, Permission... permissions) throws LoomClientException {
		DaoCollection daos = loom.internal().daos();
		User joedoe = daos.userDao().load(USER_UUID);
		Role role = daos.roleDao().createRole(ADMIN_UUID, "detection-review-test-role");
		daos.roleDao().store(role);
		for (Permission perm : permissions) {
			daos.permissionDao().grantRolePermission(role.getUuid(), perm);
		}
		Group group = daos.groupDao().create(joedoe, "detection-review-test-group");
		daos.groupDao().store(group);
		daos.groupDao().addRoleToGroup(group, role);
		daos.groupDao().addUserToGroup(group, joedoe);

		AuthLoginResponse login = client.login("joedoe", "finger").sync().body();
		client.setToken(login.getToken());
	}

}
