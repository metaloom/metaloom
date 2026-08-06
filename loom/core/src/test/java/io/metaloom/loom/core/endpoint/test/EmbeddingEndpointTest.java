package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.api.embedding.EmbeddingType;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.metaloom.utils.hash.SHA512;

import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingListResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingUpdateRequest;

public class EmbeddingEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		EmbeddingResponse embedding = client.loadEmbedding(EMBEDDING_UUID).sync().body();
		assertThat(embedding).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		EmbeddingCreateRequest request = new EmbeddingCreateRequest();
		request.setVector(new Float[] { 0.42f, 0.24f });
		request.setType(EmbeddingType.VIDEO4J_FINGERPRINT_V1.name());
		request.setAssetUuid(ASSET_UUID);
		EmbeddingResponse response = client.createEmbedding(request).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteEmbedding(EMBEDDING_UUID).sync().body();
		expect(404, "Not Found", client.loadEmbedding(EMBEDDING_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		EmbeddingUpdateRequest request = new EmbeddingUpdateRequest();
		client.updateEmbedding(EMBEDDING_UUID, request).sync().body();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		EmbeddingListResponse response = client.listEmbeddings().sync().body();
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		EmbeddingCreateRequest request = new EmbeddingCreateRequest();
		request.setVector(new Float[] { 0.42f, 0.24f });
		request.setType(EmbeddingType.VIDEO4J_FINGERPRINT_V1.name());
		request.setAssetUuid(ASSET_UUID);
		return client.createEmbedding(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadEmbedding(EMBEDDING_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listEmbeddings();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteEmbedding(EMBEDDING_UUID);
	}

	/**
	 * The write shape a Cortex node actually needs: detections first, then their vectors, linked by the uuids the first call returned.
	 */
	@Test
	public void testBulkCreateWithDetectionLinks() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = createTestAsset(client);

			DetectionBulkCreateRequest detectionRequest = new DetectionBulkCreateRequest();
			for (int i = 0; i < 3; i++) {
				detectionRequest.getDetections().add(new DetectionCreateRequest()
					.setType("face")
					.setNodeKind("facedetect")
					.setDetectionIndex(i)
					.setFrameNumber(0)
					.setBboxX(0.1f * i)
					.setBboxY(0.2f)
					.setBboxWidth(0.15f)
					.setBboxHeight(0.2f)
					.setConfidence(0.9f));
			}
			DetectionBulkResponse detections = client.bulkCreateAssetDetections(asset.getUuid(), detectionRequest).sync().body();
			assertEquals(3, detections.getCreated());

			EmbeddingBulkCreateRequest request = new EmbeddingBulkCreateRequest();
			for (int i = 0; i < 3; i++) {
				request.add(new EmbeddingCreateRequest()
					.setType("face")
					.setNodeKind("facedetect")
					.setModel("inspireface-r18")
					.setVector(new Float[] { 0.1f * i, 0.2f, 0.3f })
					.setDetectionUuid(detections.getDetections().get(i).getUuid())
					.setFrameNumber(0)
					.setSubjectIndex(i));
			}

			EmbeddingBulkResponse response = client.bulkCreateAssetEmbeddings(asset.getUuid(), request).sync().body();
			assertNotNull(response);
			assertEquals(3, response.getTotal());
			assertEquals(3, response.getCreated());
			assertEquals(0, response.getFailed());

			// The response has to carry the data back, not just the uuid: a read that cannot round-trip
			// its own write leaves a caller unable to tell what was actually stored.
			EmbeddingResponse first = response.getEmbeddings().get(0);
			assertEquals("face", first.getType());
			assertEquals("inspireface-r18", first.getModel());
			assertEquals(3, first.getDimensions().intValue());
			assertEquals(detections.getDetections().get(0).getUuid(), first.getDetectionUuid());

			EmbeddingResponse reloaded = client.loadEmbedding(first.getUuid()).sync().body();
			assertEquals("inspireface-r18", reloaded.getModel());
			assertEquals(3, reloaded.getVector().length);
		}
	}

	/**
	 * Re-running the node rewrites its own rows; raising the model adds rows beside them.
	 */
	@Test
	public void testBulkCreateUpsertsPerModel() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = createTestAsset(client);

			EmbeddingResponse first = bulkOne(client, asset, "inspireface-r18").getEmbeddings().get(0);
			EmbeddingResponse rerun = bulkOne(client, asset, "inspireface-r18").getEmbeddings().get(0);
			assertEquals(first.getUuid(), rerun.getUuid(), "A re-run under the same model must rewrite its own row");

			EmbeddingResponse upgraded = bulkOne(client, asset, "some-newer-model").getEmbeddings().get(0);
			assertNotEquals(first.getUuid(), upgraded.getUuid(), "A new model must not overwrite the old model's vectors");
			assertNotNull(client.loadEmbedding(first.getUuid()).sync().body(), "The old model's embedding must survive");
		}
	}

	private static int sha512Counter = 5000;

	private SHA512 nextSHA512() {
		sha512Counter++;
		String hex = String.format("%0128x", sha512Counter);
		return SHA512.fromString(hex);
	}

	private AssetResponse createTestAsset(LoomHttpClient client) throws LoomClientException {
		AssetCreateRequest request = new AssetCreateRequest();
		FileInfo fileInfo = new FileInfo();
		fileInfo.setMimeType(IMAGE_MIMETYPE);
		fileInfo.setFilename("embedding-test.png");
		fileInfo.setSize(1024L);
		fileInfo.setOrigin(INITIAL_ORIGIN);
		request.setFile(fileInfo);
		HashInfo hashes = new HashInfo();
		hashes.setSHA512(nextSHA512());
		request.setHashes(hashes);
		return client.createAsset(request).sync().body();
	}

	private EmbeddingBulkResponse bulkOne(LoomHttpClient client, AssetResponse asset, String model) throws Exception {
		EmbeddingBulkCreateRequest request = new EmbeddingBulkCreateRequest();
		request.add(new EmbeddingCreateRequest()
			.setType("face")
			.setNodeKind("facedetect")
			.setModel(model)
			.setVector(new Float[] { 0.5f, 0.6f, 0.7f })
			.setFrameNumber(0)
			.setSubjectIndex(0));
		return client.bulkCreateAssetEmbeddings(asset.getUuid(), request).sync().body();
	}

}
