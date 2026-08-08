package io.metaloom.cortex.node.facedetect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;

/**
 * Covers the write-back of face embeddings on the image path.
 *
 * <p>
 * The node computed no vectors at all before this: it wrote bounding boxes and nothing else, so its detections could be drawn but never matched or
 * clustered. What is pinned here is the whole chain - the detector is asked for embeddings, each vector survives to the client, and every vector is
 * linked to the uuid of the detection it was measured on.
 * </p>
 */
class FacedetectNodeEmbeddingTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final UUID ASSET_UUID = UUID.randomUUID();

	@TempDir
	File tempDir;

	private InspireFacedetector inspireface;
	private LoomHttpClient client;
	private StubLoomMedia media;
	private List<UUID> detectionUuids;

	@BeforeEach
	void setup() throws Exception {
		inspireface = mock(InspireFacedetector.class);

		File imageFile = new File(tempDir, "group.jpg");
		ImageIO.write(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);

		client = mock(LoomHttpClient.class);

		AssetResponse asset = new AssetResponse();
		asset.setUuid(ASSET_UUID);

		detectionUuids = List.of(UUID.randomUUID(), UUID.randomUUID());
		DetectionBulkResponse detections = new DetectionBulkResponse().setCreated(2).setTotal(2);
		for (UUID uuid : detectionUuids) {
			DetectionResponse row = new DetectionResponse();
			row.setUuid(uuid);
			detections.add(row);
		}

		// Every request mock is built up front: constructing one inside a thenReturn(...) starts a
		// second stubbing while the first is still open, which Mockito rejects as UnfinishedStubbing.
		LoomClientRequest<AssetResponse> assetReq = request(asset, 200);
		LoomClientRequest<DetectionBulkResponse> detectionReq = request(detections, 201);
		LoomClientRequest<EmbeddingBulkResponse> embeddingReq = request(new EmbeddingBulkResponse(), 201);
		LoomClientRequest<NodeResultResponse> nodeResultReq = request(new NodeResultResponse(), 201);

		when(client.loadAsset(any(SHA512.class))).thenReturn(assetReq);
		when(client.bulkCreateAssetDetections(any(UUID.class), any(DetectionBulkCreateRequest.class))).thenReturn(detectionReq);
		when(client.bulkCreateAssetEmbeddings(any(UUID.class), any(EmbeddingBulkCreateRequest.class))).thenReturn(embeddingReq);
		when(client.createAssetNodeResult(any(), any())).thenReturn(nodeResultReq);
	}

	private static <T extends io.metaloom.loom.rest.model.RestResponseModel<T>> LoomClientRequest<T> request(T body, int status) throws Exception {
		@SuppressWarnings("unchecked")
		LoomClientRequest<T> req = mock(LoomClientRequest.class);
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(body, status, "OK", Map.of()));
		return req;
	}

	/**
	 * Re-stub the detection bulk call to return exactly {@code count} rows.
	 *
	 * <p>
	 * The counts have to agree: the node refuses to write embeddings when the number of stored detections differs from the number it sent, because
	 * without a one-to-one correspondence there is no safe way to say which vector belongs to which row.
	 * </p>
	 */
	private List<UUID> stubDetections(int count) throws Exception {
		List<UUID> uuids = new java.util.ArrayList<>();
		DetectionBulkResponse detections = new DetectionBulkResponse().setCreated(count).setTotal(count);
		for (int i = 0; i < count; i++) {
			UUID uuid = UUID.randomUUID();
			uuids.add(uuid);
			DetectionResponse row = new DetectionResponse();
			row.setUuid(uuid);
			detections.add(row);
		}
		LoomClientRequest<DetectionBulkResponse> req = request(detections, 201);
		when(client.bulkCreateAssetDetections(any(UUID.class), any(DetectionBulkCreateRequest.class))).thenReturn(req);
		return uuids;
	}

	private static Face face(int x, int y, int w, int h, float[] embedding) {
		Face face = mock(Face.class);
		when(face.box()).thenReturn(FaceBox.create(x, y, w, h));
		when(face.getEmbedding()).thenReturn(embedding);
		return face;
	}

	private FacedetectNode node(FacedetectNodeOptions options) {
		return new FacedetectNode(client, new CortexOptions().setMetaPath(tempDir.toPath()), options,
			inspireface, mock(VideoFaceScanner.class));
	}

	@Test
	void testWritesOneEmbeddingPerFaceLinkedToItsDetection() throws Exception {
		float[] first = new float[] { 0.1f, 0.2f, 0.3f };
		float[] second = new float[] { 0.4f, 0.5f, 0.6f };
		doReturn(List.of(face(10, 20, 30, 40, first), face(100, 50, 60, 60, second)))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		node(new FacedetectNodeOptions()).process(NodeContext.create(media));

		ArgumentCaptor<EmbeddingBulkCreateRequest> captor = ArgumentCaptor.forClass(EmbeddingBulkCreateRequest.class);
		verify(client).bulkCreateAssetEmbeddings(any(UUID.class), captor.capture());

		List<EmbeddingCreateRequest> items = captor.getValue().getEmbeddings();
		assertEquals(2, items.size(), "one embedding per detected face");

		EmbeddingCreateRequest item = items.get(0);
		assertEquals("face", item.getType());
		assertEquals("facedetect", item.getNodeKind());
		// The pack-qualified form, not the bare DEFAULT_EMBEDDING_MODEL: the pack is what actually decides
		// which embedder ran, and it is folded into the model identifier so two packs' vectors never mix.
		assertEquals(new FacedetectNodeOptions().resolvedEmbeddingModel(), item.getModel());
		assertEquals(3, item.getDimensions().intValue());
		assertEquals(0, item.getSubjectIndex().intValue());
		assertThat(item.getVector()).containsExactly(0.1f, 0.2f, 0.3f);

		// The link back to the box. Without it a face hit could only say which asset matched, never
		// which face in which frame - and the detection uuids only exist once the first call returned.
		assertEquals(detectionUuids.get(0), item.getDetectionUuid());
		assertEquals(detectionUuids.get(1), items.get(1).getDetectionUuid());
		assertEquals(1, items.get(1).getSubjectIndex().intValue());
	}

	@Test
	void testAsksTheDetectorForEmbeddingsOnlyWhenEnabled() throws Exception {
		doReturn(List.of(face(10, 20, 30, 40, new float[] { 0.1f, 0.2f, 0.3f })))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		node(new FacedetectNodeOptions().setEmbeddingsEnabled(false)).process(NodeContext.create(media));

		// Switched off, the extra model pass must not be paid for and nothing must be written.
		verify(inspireface).detectFaces(any(BufferedImage.class), org.mockito.ArgumentMatchers.eq(false));
		verify(client, never()).bulkCreateAssetEmbeddings(any(UUID.class), any(EmbeddingBulkCreateRequest.class));
	}

	@Test
	void testDetectionsStillLandWhenNoFaceYieldsAVector() throws Exception {
		// A crop the recognition model cannot use leaves the box intact and simply unmatchable. Writing
		// an empty embedding batch instead would be a pointless round trip.
		doReturn(List.of(face(10, 20, 30, 40, null), face(100, 50, 60, 60, null)))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		node(new FacedetectNodeOptions()).process(NodeContext.create(media));

		verify(client).bulkCreateAssetDetections(any(UUID.class), any(DetectionBulkCreateRequest.class));
		verify(client, never()).bulkCreateAssetEmbeddings(any(UUID.class), any(EmbeddingBulkCreateRequest.class));
	}

	@Test
	void testCarriesTheConfiguredModelSoAnUpgradeIsDistinguishable() throws Exception {
		stubDetections(1);
		doReturn(List.of(face(10, 20, 30, 40, new float[] { 0.1f, 0.2f, 0.3f })))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		node(new FacedetectNodeOptions().setEmbeddingModel("some-newer-model")).process(NodeContext.create(media));

		ArgumentCaptor<EmbeddingBulkCreateRequest> captor = ArgumentCaptor.forClass(EmbeddingBulkCreateRequest.class);
		verify(client).bulkCreateAssetEmbeddings(any(UUID.class), captor.capture());

		// The model is part of the embedding's identity in Loom. Recording the configured value rather
		// than a constant is what keeps a model upgrade additive instead of destructive.
		//
		// It is additionally qualified by the model pack, because the InspireFace binding exposes no pack
		// version at runtime and two packs' vectors are not comparable - so the pack path is the only
		// evidence of which embedder produced a vector.
		EmbeddingCreateRequest item = captor.getValue().getEmbeddings().get(0);
		assertNotNull(item.getModel());
		assertEquals("some-pikachu-newer-model", item.getModel());
	}

	@Test
	void testSkipsEmbeddingsWhenTheStoredDetectionCountDisagrees() throws Exception {
		// Only one detection came back for two faces. Pairing them by position would attach the second
		// face's vector to the first face's row - wrong, permanent, and completely silent. Refusing is
		// the only safe response, and the detections themselves still stand.
		stubDetections(1);
		doReturn(List.of(face(10, 20, 30, 40, new float[] { 0.1f, 0.2f, 0.3f }), face(100, 50, 60, 60, new float[] { 0.4f, 0.5f, 0.6f })))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		node(new FacedetectNodeOptions()).process(NodeContext.create(media));

		verify(client, never()).bulkCreateAssetEmbeddings(any(UUID.class), any(EmbeddingBulkCreateRequest.class));
	}
}
