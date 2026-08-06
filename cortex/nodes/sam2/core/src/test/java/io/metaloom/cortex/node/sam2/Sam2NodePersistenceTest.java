package io.metaloom.cortex.node.sam2;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
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

import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.sam2.video.Sam2FrameSampler;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * Verifies the ledger-only persistence contract of {@link Sam2Node}: the mask bytes stay in the local
 * {@code sam2_bin} cache and only an {@code asset_node_result} row reaches Loom, stamped with the
 * checkpoint that produced them.
 *
 * <p>
 * The two {@code never()} assertions are the point of this class. "Ledger only" was a decision, not a
 * consequence, so it is asserted rather than assumed: this node deliberately does <em>not</em> write
 * {@code detection} rows, because {@code detection} has no column for polygonal geometry and stuffing
 * one into {@code meta} would be a write path with no read path.
 * </p>
 */
class Sam2NodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private Sam2Client sam2Client;
	private StubLoomMedia media;
	private CortexOptions cortexOptions;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);

		AssetResponse asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		LoomClientRequest<NodeResultResponse> nodeResultReq = mock(LoomClientRequest.class);
		when(nodeResultReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(nodeResultReq);

		sam2Client = mock(Sam2Client.class);

		File imageFile = new File(tempDir, "photo.jpg");
		ImageIO.write(new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private Sam2Node node() {
		return node(new Sam2NodeOptions());
	}

	private Sam2Node node(Sam2NodeOptions options) {
		return new Sam2Node(client, cortexOptions, options, sam2Client, new Sam2FrameSampler());
	}

	private void respondWithOneMask() throws Exception {
		when(sam2Client.segment(any(), any(), any(), any()))
			.thenReturn(Sam2TestFixtures.segmentResponse(Sam2Mode.AUTOMATIC, 120, 90, List.of(
				Sam2TestFixtures.mask(0, Sam2TestFixtures.binaryMaskPng(120, 90, 10, 10, 20, 20), 10, 10, 20, 20, 0.9d, null)), 0));
	}

	@Test
	void testRecordsLedgerWithCheckpointOnSuccess() throws Exception {
		respondWithOneMask();

		assertThat(node().process(NodeContext.create(media))).isSuccess();

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "sam2".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			// Ledger-only: the mask bytes stay local, so there is nothing to point at.
			&& r.getResultRef() == null
			// ...but which checkpoint drew those edges is recorded.
			&& ("sam2/1:" + Sam2TestFixtures.MODEL).equals(r.getProducerVersion())));

		// The ledger-only decision, asserted rather than assumed.
		verify(client, never()).bulkCreateAssetDetections(any(UUID.class), any());
		verify(client, never()).createAssetJsonComp(any(), any());
	}

	@Test
	void testRecordsFailedLedgerWhenSidecarThrows() {
		when(sam2Client.segment(any(), any(), any(), any())).thenThrow(new RuntimeException("sidecar down"));

		node().process(NodeContext.create(media));

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "sidecar down".equals(r.getReason())));
		verify(client, never()).bulkCreateAssetDetections(any(UUID.class), any());
	}

	@Test
	void testRecordsFailedLedgerWhenTrackIsAskedForAStill() {
		node(new Sam2NodeOptions().setMode(Sam2Mode.TRACK)).process(NodeContext.create(media));

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState())
				&& r.getReason() != null && r.getReason().contains("TRACK")));
	}

	@Test
	void testCacheHitPersistsOnlyOnce() throws Exception {
		respondWithOneMask();

		Sam2Node node = node();
		assertThat(node.process(NodeContext.create(media))).isSuccess();
		assertThat(node.process(NodeContext.create(media))).isSuccess();

		// On a cache hit the ledger row already exists in Loom, so re-persisting is skipped too.
		verify(client, times(1)).createAssetNodeResult(any(), any());
	}

	@Test
	void testOfflineRunWritesNothingAndStillSucceeds() throws Exception {
		respondWithOneMask();

		Sam2Node offline = new Sam2Node(null, cortexOptions, new Sam2NodeOptions(), sam2Client, new Sam2FrameSampler());

		// No Loom client at all: the node still segments and writes its masks, it simply records nothing.
		assertThat(offline.process(NodeContext.create(media))).isSuccess();
		verify(client, never()).createAssetNodeResult(any(), any());
	}
}
