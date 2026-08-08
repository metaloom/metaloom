package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterMemberCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;

/**
 * Covers the third write-back: the subjects the node believes it saw.
 *
 * <p>
 * Detection and embedding both persisted long before this; the chain then stopped, so the vectors sat in the database attributed to nobody. What is
 * pinned here is that the node groups them, that the groups reference the embedding rows it just wrote, and that the two numbers a reviewer sees -
 * how many faces, how many people - are no longer the same number.
 * </p>
 */
class FacedetectNodeClusterTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final UUID ASSET_UUID = UUID.randomUUID();

	/** Frame size of the stub image, which the normalised bounding boxes are measured against. */
	private static final int FRAME_WIDTH = 320;

	private static final int FRAME_HEIGHT = 240;

	@TempDir
	File tempDir;

	private InspireFacedetector inspireface;
	private LoomHttpClient client;
	private StubLoomMedia media;
	private List<UUID> embeddingUuids;

	@BeforeEach
	void setup() throws Exception {
		inspireface = mock(InspireFacedetector.class);

		File imageFile = new File(tempDir, "group.jpg");
		ImageIO.write(new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);

		client = mock(LoomHttpClient.class);

		AssetResponse asset = new AssetResponse();
		asset.setUuid(ASSET_UUID);

		LoomClientRequest<AssetResponse> assetReq = request(asset, 200);
		LoomClientRequest<NodeResultResponse> nodeResultReq = request(new NodeResultResponse(), 201);
		when(client.loadAsset(any(SHA512.class))).thenReturn(assetReq);
		when(client.createAssetNodeResult(any(), any())).thenReturn(nodeResultReq);
	}

	/**
	 * Three faces of one person and one of somebody else become two subjects, and each subject's membership names the embedding rows the node wrote.
	 */
	@Test
	void testWritesClustersReferencingTheStoredEmbeddings() throws Exception {
		stubWriteBack(4);
		doReturn(List.of(
			face(10, 20, 30, 40, direction(0, 0.00f)),
			face(60, 20, 30, 40, direction(0, 0.01f)),
			face(110, 20, 30, 40, direction(0, 0.02f)),
			face(200, 20, 30, 40, direction(1, 0.00f))))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		NodeResult result = node().process(NodeContext.create(media));
		assertNotNull(result);

		ArgumentCaptor<ClusterBulkCreateRequest> captor = ArgumentCaptor.forClass(ClusterBulkCreateRequest.class);
		verify(client).bulkCreateAssetClusters(any(UUID.class), captor.capture());

		List<ClusterCreateItem> clusters = captor.getValue().getClusters();
		assertEquals(2, clusters.size(), "three faces of one person plus one of another is two subjects");

		// Every member must name an embedding the node actually stored - that reference is the only thing
		// connecting a cluster to the faces it is made of.
		List<String> members = new ArrayList<>();
		for (ClusterCreateItem cluster : clusters) {
			assertEquals("face", cluster.getType());
			assertEquals("facedetect", cluster.getNodeKind());
			assertNotNull(cluster.getClusterIndex(), "the cluster index is the upsert key and cannot be null");
			assertNotNull(cluster.getCentroid());
			assertEquals(cluster.getCentroid().length, cluster.getDimensions().intValue());
			for (ClusterMemberCreateItem member : cluster.getMembers()) {
				members.add(member.getEmbeddingUuid());
				assertEquals("AUTO", member.getOrigin());
				assertNotNull(member.getConfidence());
			}
		}
		assertEquals(4, members.size(), "every embedded face belongs to exactly one subject");
		org.assertj.core.api.Assertions.assertThat(members).containsExactlyInAnyOrderElementsOf(embeddingUuids.stream().map(UUID::toString).toList());

		// Indices are dense from 0: they are the (asset, node_kind, cluster_index) upsert key.
		org.assertj.core.api.Assertions.assertThat(clusters.stream().map(ClusterCreateItem::getClusterIndex)).containsExactlyInAnyOrder(0, 1);
	}

	/**
	 * {@code face_count} reports people, not boxes. Its own {@code @PortDoc} always said so; it emitted the detection count until clustering existed.
	 */
	@Test
	void testFaceCountIsTheSubjectCountNotTheDetectionCount() throws Exception {
		stubWriteBack(3);
		doReturn(List.of(
			face(10, 20, 30, 40, direction(0, 0.00f)),
			face(60, 20, 30, 40, direction(0, 0.01f)),
			face(110, 20, 30, 40, direction(0, 0.02f))))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		NodeResult result = node().process(NodeContext.create(media));

		assertEquals(1L, result.get(FacedetectNode.OUT_FACE_COUNT), "three views of one person is one person");
		assertThat(result).hasElementCount(FacedetectNode.OUT_DETECTIONS, 3);
	}

	/**
	 * A face that matches nobody is still somebody. Discarding DBSCAN's noise points would make a portrait report no people at all.
	 */
	@Test
	void testALoneFaceIsReportedAsOneSubject() throws Exception {
		stubWriteBack(1);
		doReturn(List.of(face(10, 20, 30, 40, direction(0, 0f))))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		NodeResult result = node().process(NodeContext.create(media));

		assertEquals(1L, result.get(FacedetectNode.OUT_FACE_COUNT));

		ArgumentCaptor<ClusterBulkCreateRequest> captor = ArgumentCaptor.forClass(ClusterBulkCreateRequest.class);
		verify(client).bulkCreateAssetClusters(any(UUID.class), captor.capture());
		ClusterCreateItem cluster = captor.getValue().getClusters().get(0);
		assertEquals(1, cluster.getMembers().size());
		assertNotNull(cluster.getMeta());
		assertTrue(cluster.getMeta().getBoolean("noise"), "an uncorroborated subject is flagged, not hidden");
	}

	/**
	 * With embeddings switched off there is nothing to cluster - but the faces were still found, and reporting zero would turn a degraded result into
	 * an apparently empty one.
	 */
	@Test
	void testFacesAreStillCountedWhenEmbeddingsAreDisabled() throws Exception {
		stubWriteBack(2);
		doReturn(List.of(face(10, 20, 30, 40, null), face(60, 20, 30, 40, null)))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		NodeResult result = node(new FacedetectNodeOptions().setEmbeddingsEnabled(false)).process(NodeContext.create(media));

		assertEquals(2L, result.get(FacedetectNode.OUT_FACE_COUNT), "two unattributable faces are still two faces");
		verify(client, org.mockito.Mockito.never()).bulkCreateAssetClusters(any(UUID.class), any(ClusterBulkCreateRequest.class));
	}

	/**
	 * Bounding boxes are written as a 0-1 factor of the frame, which is the convention the column has always documented.
	 */
	@Test
	void testBoundingBoxesArePersistedAsNormalisedFactors() throws Exception {
		stubWriteBack(1);
		doReturn(List.of(face(32, 24, 64, 48, direction(0, 0f))))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		node().process(NodeContext.create(media));

		ArgumentCaptor<DetectionBulkCreateRequest> captor = ArgumentCaptor.forClass(DetectionBulkCreateRequest.class);
		verify(client).bulkCreateAssetDetections(any(UUID.class), captor.capture());

		DetectionCreateRequest row = captor.getValue().getDetections().get(0);
		assertEquals(32f / FRAME_WIDTH, row.getBboxX(), 0.0001f);
		assertEquals(24f / FRAME_HEIGHT, row.getBboxY(), 0.0001f);
		assertEquals(64f / FRAME_WIDTH, row.getBboxWidth(), 0.0001f);
		assertEquals(48f / FRAME_HEIGHT, row.getBboxHeight(), 0.0001f);
	}

	/**
	 * The ledger points at what the node produced.
	 *
	 * <p>
	 * It used to call {@code resultRef("detection")} with no uuids at all, and that helper answers null for an empty varargs - so {@code result_ref} was
	 * permanently empty even though the uuids were in hand two lines earlier.
	 * </p>
	 */
	@Test
	void testLedgerCarriesTheClusterUuids() throws Exception {
		stubWriteBack(2);
		doReturn(List.of(face(10, 20, 30, 40, direction(0, 0f)), face(200, 20, 30, 40, direction(1, 0f))))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		node().process(NodeContext.create(media));

		ArgumentCaptor<NodeResultCreateRequest> captor = ArgumentCaptor.forClass(NodeResultCreateRequest.class);
		verify(client).createAssetNodeResult(any(), captor.capture());

		io.vertx.core.json.JsonObject resultRef = captor.getValue().getResultRef();
		assertNotNull(resultRef, "the ledger entry must point at something");
		assertTrue(resultRef.encode().contains("cluster"),
			"the reference should name the node's headline output, got: " + resultRef.encode());
	}

	// ---------------------------------------------------------------------------------------------

	/**
	 * Stub the detection and embedding bulk writes to return {@code count} rows each, so the node can pair vectors with boxes and clusters with vectors.
	 */
	private void stubWriteBack(int count) throws Exception {
		DetectionBulkResponse detections = new DetectionBulkResponse().setCreated(count).setTotal(count);
		EmbeddingBulkResponse embeddings = new EmbeddingBulkResponse();
		embeddingUuids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			DetectionResponse detection = new DetectionResponse();
			detection.setUuid(UUID.randomUUID());
			detections.add(detection);

			UUID embeddingUuid = UUID.randomUUID();
			embeddingUuids.add(embeddingUuid);
			EmbeddingResponse embedding = new EmbeddingResponse();
			embedding.setUuid(embeddingUuid);
			embeddings.add(embedding);
		}

		ClusterBulkResponse clusters = new ClusterBulkResponse();
		ClusterResponse cluster = new ClusterResponse();
		cluster.setUuid(UUID.randomUUID());
		clusters.add(cluster);

		LoomClientRequest<DetectionBulkResponse> detectionReq = request(detections, 201);
		LoomClientRequest<EmbeddingBulkResponse> embeddingReq = request(embeddings, 201);
		LoomClientRequest<ClusterBulkResponse> clusterReq = request(clusters, 201);
		when(client.bulkCreateAssetDetections(any(UUID.class), any(DetectionBulkCreateRequest.class))).thenReturn(detectionReq);
		when(client.bulkCreateAssetEmbeddings(any(UUID.class), any(EmbeddingBulkCreateRequest.class))).thenReturn(embeddingReq);
		when(client.bulkCreateAssetClusters(any(UUID.class), any(ClusterBulkCreateRequest.class))).thenReturn(clusterReq);
	}

	private static <T extends io.metaloom.loom.rest.model.RestResponseModel<T>> LoomClientRequest<T> request(T body, int status) throws Exception {
		@SuppressWarnings("unchecked")
		LoomClientRequest<T> req = mock(LoomClientRequest.class);
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(body, status, "OK", Map.of()));
		return req;
	}

	private FacedetectNode node() {
		return node(new FacedetectNodeOptions());
	}

	private FacedetectNode node(FacedetectNodeOptions options) {
		return new FacedetectNode(client, new CortexOptions(), options, inspireface, new VideoFaceScanner(inspireface));
	}

	/** A unit vector along one axis, nudged by a hair - far closer than eps, so a shared axis means a shared subject. */
	private static float[] direction(int axis, float nudge) {
		float[] vector = new float[8];
		vector[axis] = 1f;
		vector[(axis + 1) % vector.length] = nudge;
		return vector;
	}

	private static Face face(int x, int y, int w, int h, float[] embedding) {
		Face face = mock(Face.class);
		when(face.box()).thenReturn(FaceBox.create(x, y, w, h));
		when(face.getEmbedding()).thenReturn(embedding);
		return face;
	}

}
