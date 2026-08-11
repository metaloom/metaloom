package io.metaloom.cortex.node.facedetect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
import io.metaloom.cortex.common.node.PipelineConfigurable;
import io.metaloom.cortex.node.facedetect.video.VideoFaceScanner;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.vertx.core.json.JsonObject;

/**
 * The clustering radius is authored per pipeline node, not per worker.
 *
 * <p>
 * {@code faceClusterEPS} has been a declared, validated, descriptor-advertised option since clustering landed, so the pipeline editor has always
 * offered a "Cluster Radius" field for it - and {@code RegistryNodeRegistrar.adapt(...)} passes the node definition only to a
 * {@link PipelineConfigurable}, which this node was not. Every value an author typed was therefore dropped at the worker and the run silently
 * clustered at whatever {@code cortex.yml} said. That is the defect these tests pin.
 * </p>
 *
 * <p>
 * The evidence is deliberately behavioural rather than a getter check: what matters is that DBSCAN <em>groups differently</em>, because a radius that
 * is stored but not handed to {@code FaceClusterer} is exactly the bug being fixed.
 * </p>
 */
class FacedetectNodePipelineConfigTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final UUID ASSET_UUID = UUID.randomUUID();

	private static final int FRAME_WIDTH = 320;

	private static final int FRAME_HEIGHT = 240;

	/**
	 * Cosine distance between the two stub faces below.
	 *
	 * <p>
	 * Normalising {@code (1, 0.5)} gives a dot product of {@code 1/sqrt(1.25)}, so the pair sits at ~0.106 - comfortably inside the 0.6 default and
	 * comfortably outside the tightened radius the tests configure. Both assertions therefore turn on the radius alone.
	 * </p>
	 */
	private static final float TIGHTER_THAN_THE_PAIR = 0.05f;

	@TempDir
	File tempDir;

	private InspireFacedetector inspireface;
	private LoomHttpClient client;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		inspireface = mock(InspireFacedetector.class);

		File imageFile = new File(tempDir, "pair.jpg");
		ImageIO.write(new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);

		client = mock(LoomHttpClient.class);

		AssetResponse asset = new AssetResponse();
		asset.setUuid(ASSET_UUID);

		// Built into locals first: request(...) stubs a mock of its own, and Mockito rejects that
		// happening inside an unfinished when(...).thenReturn(...).
		LoomClientRequest<AssetResponse> assetReq = request(asset, 200);
		LoomClientRequest<NodeResultResponse> nodeResultReq = request(new NodeResultResponse(), 201);
		when(client.loadAsset(any(SHA512.class))).thenReturn(assetReq);
		when(client.createAssetNodeResult(any(), any())).thenReturn(nodeResultReq);

		stubWriteBack(2);
		doReturn(List.of(
			face(10, 20, 30, 40, vector(1f, 0f)),
			face(60, 20, 30, 40, vector(1f, 0.5f))))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());
	}

	@Test
	void testWorkerDefaultGroupsThePairAsOneSubject() throws Exception {
		// The baseline the other cases are measured against: at the 0.6 default these two faces are one person.
		assertThat(clusterCountFor(null)).isEqualTo(1);
	}

	@Test
	void testConfiguredRadiusChangesHowTheNodeGroups() throws Exception {
		// The whole point: the same two faces, the same worker options, a different answer - because the
		// pipeline node said so.
		JsonObject nodeDef = new JsonObject().put("id", "strict-faces").put("type", "facedetect")
			.put("faceClusterEPS", TIGHTER_THAN_THE_PAIR);

		assertThat(clusterCountFor(nodeDef))
			.as("a radius tighter than the pair's distance must split them into two subjects")
			.isEqualTo(2);
	}

	@Test
	void testConfiguredMinimumChangesHowTheNodeGroups() throws Exception {
		// eps and minPoints are the two halves of one algorithm; configuring only one would leave the
		// editor showing two adjacent fields where one works.
		JsonObject nodeDef = new JsonObject().put("id", "strict-faces").put("type", "facedetect")
			.put("faceClusterMinimum", 3);

		assertThat(clusterCountFor(nodeDef))
			.as("a neighbourhood larger than the pair leaves both faces unattributed")
			.isEqualTo(2);
	}

	@Test
	void testAnUnconfiguredNodeKeepsTheWorkerValue() {
		// A definition that mentions neither key must not reset either to a hardcoded default.
		FacedetectNodeOptions options = new FacedetectNodeOptions().setFaceClusterEPS(0.42f).setFaceClusterMinimum(4);
		FacedetectNode node = node(options);
		node.configure(new JsonObject().put("id", "plain").put("type", "facedetect"));

		assertThat(node.faceClusterEPS()).isEqualTo(0.42f);
		assertThat(node.faceClusterMinimum()).isEqualTo(4);
	}

	/**
	 * The worker's options object is shared, so configuring one node must not reconfigure the others.
	 *
	 * <p>
	 * When {@code cortex.yml} carries a {@code facedetection} block, {@code AbstractNodeModule.nodeOptions(...)} returns the same instance to every
	 * injection point. Writing the per-instance value back into it - which is what the older
	 * {@link PipelineConfigurable} nodes do - would let one node in a graph silently retune every other one, and only on the workers whose YAML
	 * happens to set the key.
	 * </p>
	 */
	@Test
	void testConfiguringOneNodeLeavesTheSharedOptionsAlone() {
		FacedetectNodeOptions shared = new FacedetectNodeOptions();
		float workerValue = shared.getFaceClusterEPS();

		FacedetectNode strict = node(shared);
		strict.configure(new JsonObject().put("id", "strict").put("faceClusterEPS", TIGHTER_THAN_THE_PAIR));

		FacedetectNode relaxed = node(shared);

		assertThat(strict.faceClusterEPS()).isEqualTo(TIGHTER_THAN_THE_PAIR);
		assertThat(relaxed.faceClusterEPS()).as("a second node on the same worker options is untouched").isEqualTo(workerValue);
		assertThat(shared.getFaceClusterEPS()).as("the worker's own configuration is untouched").isEqualTo(workerValue);
	}

	/**
	 * A misconfigured node fails loudly at build time.
	 *
	 * <p>
	 * {@code FacedetectNodeOptions.validate()} already rejects a non-positive radius, but it only ever sees the worker's options - a per-instance
	 * value never passes through it. Without the check here a {@code 0} typed in the editor would reach DBSCAN, where every face is its own subject
	 * and the run merely looks disappointing.
	 * </p>
	 */
	@Test
	void testNonPositiveValuesAreRejected() {
		assertThatThrownBy(() -> node().configure(new JsonObject().put("id", "zero").put("faceClusterEPS", 0)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("faceClusterEPS")
			.hasMessageContaining("zero");

		assertThatThrownBy(() -> node().configure(new JsonObject().put("id", "negative").put("faceClusterEPS", -0.5f)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("faceClusterEPS");

		assertThatThrownBy(() -> node().configure(new JsonObject().put("id", "zero-min").put("faceClusterMinimum", 0)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("faceClusterMinimum");

		// Positive as a Number, zero as a neighbourhood size - so the check has to happen after the narrowing.
		assertThatThrownBy(() -> node().configure(new JsonObject().put("id", "fractional-min").put("faceClusterMinimum", 0.5)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("faceClusterMinimum");
	}

	@Test
	void testANonNumericRadiusIsRejected() {
		assertThatThrownBy(() -> node().configure(new JsonObject().put("id", "texty").put("faceClusterEPS", "wide")))
			.isInstanceOf(IllegalStateException.class);
	}

	/**
	 * Run the node and report how many subjects it wrote back.
	 *
	 * @param nodeDef the pipeline node definition, or null to leave the node unconfigured
	 */
	private int clusterCountFor(JsonObject nodeDef) throws Exception {
		FacedetectNode node = node();
		if (nodeDef != null) {
			node.configure(nodeDef);
		}
		node.process(NodeContext.create(media));

		ArgumentCaptor<ClusterBulkCreateRequest> captor = ArgumentCaptor.forClass(ClusterBulkCreateRequest.class);
		verify(client).bulkCreateAssetClusters(any(UUID.class), captor.capture());
		return captor.getValue().getClusters().size();
	}

	private void stubWriteBack(int count) throws Exception {
		DetectionBulkResponse detections = new DetectionBulkResponse().setCreated(count).setTotal(count);
		EmbeddingBulkResponse embeddings = new EmbeddingBulkResponse();
		for (int i = 0; i < count; i++) {
			DetectionResponse detection = new DetectionResponse();
			detection.setUuid(UUID.randomUUID());
			detections.add(detection);

			EmbeddingResponse embedding = new EmbeddingResponse();
			embedding.setUuid(UUID.randomUUID());
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

	/** An 8-wide vector carrying the two components that decide the pair's distance; the rest stay zero. */
	private static float[] vector(float first, float second) {
		float[] embedding = new float[8];
		embedding[0] = first;
		embedding[1] = second;
		return embedding;
	}

	private static Face face(int x, int y, int w, int h, float[] embedding) {
		Face face = mock(Face.class);
		when(face.box()).thenReturn(FaceBox.create(x, y, w, h));
		when(face.getEmbedding()).thenReturn(embedding);
		return face;
	}
}
