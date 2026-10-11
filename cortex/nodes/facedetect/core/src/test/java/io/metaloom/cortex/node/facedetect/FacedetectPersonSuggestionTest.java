package io.metaloom.cortex.node.facedetect;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.cluster.ClusterBulkCreateRequest;
import io.metaloom.loom.rest.model.cluster.ClusterBulkResponse;
import io.metaloom.loom.rest.model.cluster.ClusterCreateItem;
import io.metaloom.loom.rest.model.cluster.ClusterResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkCreateRequest;
import io.metaloom.loom.rest.model.embedding.EmbeddingBulkResponse;
import io.metaloom.loom.rest.model.embedding.EmbeddingResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.person.PersonMatchCandidate;
import io.metaloom.loom.rest.model.person.PersonMatchRequest;
import io.metaloom.loom.rest.model.person.PersonMatchResponse;
import io.metaloom.loom.rest.model.person.PersonMatchResult;
import io.metaloom.loom.rest.model.person.PersonPendingSample;
import io.metaloom.loom.rest.model.person.PersonPrintCreateRequest;
import io.metaloom.loom.rest.model.person.PersonPrintResponse;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video.facedetect.face.Face;
import io.metaloom.video.facedetect.face.FaceBox;
import io.metaloom.video.facedetect.inspireface.InspireFacedetector;
import io.vertx.core.json.JsonArray;

/**
 * Person suggestions on face clusters, and the enrolment of a person's own pictures as face references. Detector and Loom are mocked.
 */
class FacedetectPersonSuggestionTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	private static final UUID ASSET_UUID = UUID.randomUUID();

	private static final UUID ANNA = UUID.fromString("0a0a0a0a-0000-4000-8000-00000000000a");

	private static final UUID PICTURE = UUID.fromString("0c0c0c0c-0000-4000-8000-00000000000c");

	@TempDir
	File tempDir;

	private InspireFacedetector inspireface;

	private LoomHttpClient client;

	private StubLoomMedia media;

	private final List<PersonMatchRequest> matchRequests = new ArrayList<>();

	@BeforeEach
	void setup() throws Exception {
		inspireface = mock(InspireFacedetector.class);
		File imageFile = new File(tempDir, "portrait.jpg");
		ImageIO.write(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);

		client = mock(LoomHttpClient.class);
		AssetResponse asset = new AssetResponse();
		asset.setUuid(ASSET_UUID);
		LoomClientRequest<AssetResponse> assetReq = request(asset);
		when(client.loadAsset(any(SHA512.class))).thenReturn(assetReq);
		LoomClientRequest<NodeResultResponse> ledgerReq = request(new NodeResultResponse());
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);
		LoomClientRequest<PersonPrintResponse> printReq = request(new PersonPrintResponse());
		when(client.storePersonImagePrint(any(UUID.class), any(UUID.class), any())).thenReturn(printReq);

		// Two faces of two different people: two clusters (the second a noise singleton).
		DetectionBulkResponse detections = new DetectionBulkResponse();
		EmbeddingBulkResponse embeddings = new EmbeddingBulkResponse();
		for (int i = 0; i < 2; i++) {
			DetectionResponse d = new DetectionResponse();
			d.setUuid(UUID.randomUUID());
			detections.add(d);
			EmbeddingResponse e = new EmbeddingResponse();
			e.setUuid(UUID.randomUUID());
			embeddings.add(e);
		}
		LoomClientRequest<DetectionBulkResponse> detectionReq = request(detections);
		LoomClientRequest<EmbeddingBulkResponse> embeddingReq = request(embeddings);
		when(client.bulkCreateAssetDetections(any(UUID.class), any())).thenReturn(detectionReq);
		when(client.bulkCreateAssetEmbeddings(any(UUID.class), any(EmbeddingBulkCreateRequest.class))).thenReturn(embeddingReq);
		when(client.bulkCreateAssetClusters(any(UUID.class), any(ClusterBulkCreateRequest.class))).thenAnswer(inv -> {
			ClusterBulkCreateRequest req = inv.getArgument(1);
			ClusterBulkResponse body = new ClusterBulkResponse();
			for (ClusterCreateItem item : req.getClusters()) {
				ClusterResponse r = new ClusterResponse().setType(item.getType()).setReviewStatus("PENDING")
					.setSuggestedPersonUuid(item.getSuggestedPersonUuid()).setSuggestedScore(item.getSuggestedScore());
				r.setUuid(UUID.randomUUID());
				body.add(r);
			}
			return request(body);
		});

		doReturn(List.of(face(10, 20, 30, 40, axis(0)), face(200, 20, 30, 40, axis(1))))
			.when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());
	}

	/** First match: the given pending samples and no candidates; every later one suggests Anna for the first probe. */
	private void matchAnswers(boolean annaFromTheStart, PersonPendingSample... pending) throws Exception {
		AtomicInteger calls = new AtomicInteger();
		when(client.matchPersons(any())).thenAnswer(inv -> {
			PersonMatchRequest request = inv.getArgument(0);
			matchRequests.add(request);
			boolean first = calls.getAndIncrement() == 0;
			PersonMatchResponse response = new PersonMatchResponse();
			for (int i = 0; i < request.getVectors().size(); i++) {
				PersonMatchResult result = new PersonMatchResult().setIndex(i);
				if (i == 0 && (annaFromTheStart || !first)) {
					result.getCandidates().add(new PersonMatchCandidate().setPersonUuid(ANNA.toString()).setName("Anna Meyer").setScore(0.58f));
				}
				response.getResults().add(result);
			}
			if (first) {
				response.getPendingSamples().addAll(List.of(pending));
			}
			return request(response);
		});
	}

	private void pictureDownload() throws Exception {
		ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);
		LoomBinaryResponse binary = mock(LoomBinaryResponse.class);
		when(binary.getStream()).thenReturn(new ByteArrayInputStream(jpeg.toByteArray()));
		@SuppressWarnings("unchecked")
		LoomClientRequest<LoomBinaryResponse> r = mock(LoomClientRequest.class);
		when(r.sync()).thenReturn(new LoomClientResponseImpl<>(binary, 200, "OK", Map.of()));
		when(client.downloadPersonImage(any(UUID.class), any(UUID.class))).thenReturn(r);
	}

	private FacedetectNode node(FacedetectNodeOptions options) {
		return new FacedetectNode(client, new CortexOptions(), options, inspireface, new VideoFaceScanner(inspireface));
	}

	private List<ClusterCreateItem> writtenClusters() {
		ArgumentCaptor<ClusterBulkCreateRequest> captor = ArgumentCaptor.forClass(ClusterBulkCreateRequest.class);
		verify(client).bulkCreateAssetClusters(any(UUID.class), captor.capture());
		return captor.getValue().getClusters();
	}

	@Test
	void testTheClosestKnownFaceIsSuggested() throws Exception {
		matchAnswers(true);
		NodeResult result = node(new FacedetectNodeOptions()).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		PersonMatchRequest request = matchRequests.get(0);
		assertEquals("face", request.getType());
		assertEquals(new FacedetectNodeOptions().resolvedEmbeddingModel(), request.getModel());
		assertEquals(FacedetectNodeOptions.DEFAULT_SUGGEST_THRESHOLD, request.getThreshold(), 1e-6);

		List<ClusterCreateItem> clusters = writtenClusters();
		assertEquals(ANNA.toString(), clusters.get(0).getSuggestedPersonUuid());
		assertNull(clusters.get(1).getSuggestedPersonUuid());

		JsonArray persons = new JsonArray(result.get(FacedetectNode.OUT_PERSONS));
		assertEquals(1, persons.size());
		assertEquals("face", persons.getJsonObject(0).getString("source"));
		assertEquals("SUGGESTED", persons.getJsonObject(0).getString("status"));
	}

	/** Without a vector there is nothing to compare: no embeddings, no matching - and the persons port is still written. */
	@Test
	void testNoEmbeddingsMeansNoMatching() throws Exception {
		matchAnswers(true);
		NodeResult result = node(new FacedetectNodeOptions().setEmbeddingsEnabled(false)).process(NodeContext.create(media));
		assertThat(result).isSuccess();
		verify(client, never()).matchPersons(any());
	}

	/**
	 * A picture uploaded to a person becomes a face reference in this node's model: the largest face in it, stored as a print, and the clusters
	 * matched again.
	 */
	@Test
	void testAPersonPictureIsEnrolledWithItsLargestFace() throws Exception {
		matchAnswers(false, new PersonPendingSample().setPersonUuid(ANNA.toString()).setSampleUuid(PICTURE.toString()).setMimeType("image/jpeg"));
		pictureDownload();
		// The picture holds a small face and a large one; the large one is the person. Built before stubbing: Mockito refuses a mock created
		// halfway through another stubbing.
		List<Face> item = List.of(face(10, 20, 30, 40, axis(0)), face(200, 20, 30, 40, axis(1)));
		List<Face> picture = List.of(face(0, 0, 8, 8, axis(2)), face(10, 10, 40, 40, axis(3)));
		doReturn(item).doReturn(picture).when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		assertThat(node(new FacedetectNodeOptions()).process(NodeContext.create(media))).isSuccess();

		verify(client).storePersonImagePrint(eq(ANNA), eq(PICTURE), argThat((PersonPrintCreateRequest p) -> "face".equals(p.getType())
			&& p.getVector() != null && p.getVector()[3] == 1f && p.getVector()[2] == 0f));
		assertEquals(2, matchRequests.size(), "matched again once the picture was enrolled");
		assertEquals(ANNA.toString(), writtenClusters().get(0).getSuggestedPersonUuid());
	}

	@Test
	void testAPictureWithoutAFaceIsRecordedAsUnusable() throws Exception {
		matchAnswers(false, new PersonPendingSample().setPersonUuid(ANNA.toString()).setSampleUuid(PICTURE.toString()).setMimeType("image/jpeg"));
		pictureDownload();
		List<Face> item = List.of(face(10, 20, 30, 40, axis(0)), face(200, 20, 30, 40, axis(1)));
		doReturn(item).doReturn(List.of()).when(inspireface).detectFaces(any(BufferedImage.class), anyBoolean());

		assertThat(node(new FacedetectNodeOptions()).process(NodeContext.create(media))).isSuccess();
		verify(client).storePersonImagePrint(eq(ANNA), eq(PICTURE), argThat((PersonPrintCreateRequest p) -> p.getVector() == null
			&& "no face found".equals(p.getError())));
		assertEquals(1, matchRequests.size());
	}

	private static <T extends io.metaloom.loom.rest.model.RestResponseModel<T>> LoomClientRequest<T> request(T body) throws Exception {
		@SuppressWarnings("unchecked")
		LoomClientRequest<T> req = mock(LoomClientRequest.class);
		when(req.sync()).thenReturn(new LoomClientResponseImpl<>(body, 200, "OK", Map.of()));
		return req;
	}

	private static float[] axis(int axis) {
		float[] vector = new float[8];
		vector[axis] = 1f;
		return vector;
	}

	private static Face face(int x, int y, int w, int h, float[] embedding) {
		Face face = mock(Face.class);
		when(face.box()).thenReturn(FaceBox.create(x, y, w, h));
		when(face.getEmbedding()).thenReturn(embedding);
		return face;
	}
}
