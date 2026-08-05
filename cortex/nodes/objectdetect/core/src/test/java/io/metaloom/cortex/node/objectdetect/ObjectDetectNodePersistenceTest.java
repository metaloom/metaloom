package io.metaloom.cortex.node.objectdetect;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.payload.BoundingBox;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.objectdetect.video.VideoObjectScanner;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.detection.DetectionBulkCreateRequest;
import io.metaloom.loom.rest.model.detection.DetectionBulkResponse;
import io.metaloom.loom.rest.model.detection.DetectionCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * The two-step persistence contract: the detections go to {@code assets/:uuid/detections/bulk} and
 * <em>then</em> one {@code asset_node_result} ledger row records that the node ran.
 *
 * <p>
 * The detail worth pinning is {@code label}. It is an indexed column added to the {@code detection}
 * table specifically for object detection — "Detected class for object detection, e.g. dog" — and
 * {@code facedetect}, the only previous writer, has never set it. A regression that stopped
 * populating it would leave every row queryable only by box.
 * </p>
 */
class ObjectDetectNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private ObjectDetector detector;
	private StubLoomMedia media;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		detector = mock(ObjectDetector.class);
		client = mock(LoomHttpClient.class);

		AssetResponse asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		LoomClientRequest<DetectionBulkResponse> bulkReq = mock(LoomClientRequest.class);
		when(bulkReq.sync()).thenReturn(new LoomClientResponseImpl<>(new DetectionBulkResponse(), 201, "Created", Map.of()));
		when(client.bulkCreateAssetDetections(any(UUID.class), any())).thenReturn(bulkReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		File imageFile = new File(tempDir, "street.jpg");
		ImageIO.write(new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private ObjectDetectNode node() {
		return new ObjectDetectNode(client, new CortexOptions().setMetaPath(tempDir.toPath()),
			new ObjectDetectNodeOptions(), detector, new VideoObjectScanner(detector));
	}

	private static ObjectDetection object(int x, int y, int w, int h, float confidence, int classId, String label) {
		return new ObjectDetection(new BoundingBox(x, y, w, h), confidence, classId, label);
	}

	@Test
	void testWritesTheDetectionsWithTheirLabels() {
		doReturn(List.of(
			object(10, 20, 30, 40, 0.91f, 14, "person"),
			object(100, 50, 60, 60, 0.72f, 6, "car"))).when(detector).detect(any(BufferedImage.class));

		assertThat(node().process(NodeContext.create(media))).isSuccess();

		ArgumentCaptor<DetectionBulkCreateRequest> bulk = ArgumentCaptor.forClass(DetectionBulkCreateRequest.class);
		verify(client, times(1)).bulkCreateAssetDetections(eq(assetUuid), bulk.capture());

		List<DetectionCreateRequest> items = bulk.getValue().getDetections();
		assertEquals(2, items.size());

		DetectionCreateRequest first = items.get(0);
		assertEquals("objectdetection", first.getType(), "the type the V2.43 schema comment names");
		assertEquals("objectdetect", first.getNodeKind());
		assertEquals("person", first.getLabel(), "the indexed label column is the point of this node");
		assertEquals(0, first.getDetectionIndex());
		assertEquals(0, first.getFrameNumber());
		assertEquals(10f, first.getBboxX());
		assertEquals(20f, first.getBboxY());
		assertEquals(30f, first.getBboxWidth());
		assertEquals(40f, first.getBboxHeight());
		assertEquals(0.91f, first.getConfidence());
		assertNotNull(first.getProducerVersion());

		// Dense ordinals within the frame: the unique key is (asset, kind, frame, index), so a repeated
		// index would make the second row overwrite the first.
		assertEquals(1, items.get(1).getDetectionIndex());
		assertEquals("car", items.get(1).getLabel());
	}

	@Test
	void testRecordsExactlyOneSuccessfulLedgerRow() {
		doReturn(List.of(object(10, 20, 30, 40, 0.9f, 14, "person"))).when(detector).detect(any(BufferedImage.class));

		assertThat(node().process(NodeContext.create(media))).isSuccess();

		ArgumentCaptor<NodeResultCreateRequest> ledger = ArgumentCaptor.forClass(NodeResultCreateRequest.class);
		verify(client, times(1)).createAssetNodeResult(eq(assetUuid), ledger.capture());

		NodeResultCreateRequest row = ledger.getValue();
		assertEquals("objectdetect", row.getNodeKind());
		assertEquals(ResultState.SUCCESS.name(), row.getState());
		assertEquals("COMPUTED", row.getOrigin());
		// The model is part of the version because it changes the meaning of the output: the same pixels
		// scanned with a VOC and a COCO model carry different labels.
		assertEquals("objectdetect/1:YOLOv11n_voc.onnx", row.getProducerVersion());
	}

	@Test
	void testAnEmptyResultStillRecordsThatTheNodeRan() {
		doReturn(List.of()).when(detector).detect(any(BufferedImage.class));

		assertThat(node().process(NodeContext.create(media))).isSuccess();

		// "Scanned, found nothing" is a real answer and has to be distinguishable from "never scanned",
		// which is what the ledger row is for. The bulk call still goes out, emptying any rows a previous
		// run left behind is Loom's business.
		verify(client, times(1)).createAssetNodeResult(eq(assetUuid), any());
		verify(client, times(1)).bulkCreateAssetDetections(eq(assetUuid), any());
	}

	@Test
	void testAFailedDetectionRecordsAFailedLedgerRowAndWritesNoDetections() {
		doThrow(new IllegalStateException("model missing")).when(detector).detect(any(BufferedImage.class));

		NodeResult result = node().process(NodeContext.create(media));
		assertEquals(ResultState.FAILED, result.getState());

		verify(client, never()).bulkCreateAssetDetections(any(UUID.class), any());

		ArgumentCaptor<NodeResultCreateRequest> ledger = ArgumentCaptor.forClass(NodeResultCreateRequest.class);
		verify(client, times(1)).createAssetNodeResult(eq(assetUuid), ledger.capture());
		assertEquals(ResultState.FAILED.name(), ledger.getValue().getState());
		assertEquals("model missing", ledger.getValue().getReason());
	}

	@Test
	void testACacheHitDoesNotPersistTwice() {
		doReturn(List.of(object(10, 20, 30, 40, 0.9f, 14, "person"))).when(detector).detect(any(BufferedImage.class));

		ObjectDetectNode node = node();
		assertThat(node.process(NodeContext.create(media))).isSuccess();
		assertThat(node.process(NodeContext.create(media))).isSuccess();

		// The durable copy already exists in Loom, so a cache hit re-emits the ports and writes nothing.
		verify(client, times(1)).bulkCreateAssetDetections(eq(assetUuid), any());
		verify(client, times(1)).createAssetNodeResult(eq(assetUuid), any());
		verify(detector, times(1)).detect(any(BufferedImage.class));
	}
}
