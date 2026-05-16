package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionListResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
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
		DetectionCreateRequest request = new DetectionCreateRequest();
		request.setType("facedetection");
		request.setFrameNumber(0);
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

}
