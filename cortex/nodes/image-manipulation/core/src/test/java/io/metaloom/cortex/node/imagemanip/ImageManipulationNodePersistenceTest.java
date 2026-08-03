package io.metaloom.cortex.node.imagemanip;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;

/**
 * The persistence contract: this is a <strong>ledger-only</strong> node.
 *
 * <p>
 * The reframed bytes stay in the worker's {@code imagemanip_bin} cache, so exactly one {@code asset_node_result} row is written and it carries no
 * {@code result_ref} - there is no payload row for it to point at. A {@code result_ref} appearing here would be a claim that Loom holds the image,
 * which it does not.
 * </p>
 */
class ImageManipulationNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();

	private LoomHttpClient client;

	private CortexOptions cortexOptions;

	private StubLoomMedia media;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		client = mock(LoomHttpClient.class);

		AssetResponse asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		File file = ImageManipFixtures.writePng(tempDir, "asset.png", ImageManipFixtures.quadrants(80, 40));
		media = new StubLoomMedia(file.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private ImageManipulationNode node(ImageManipulationNodeOptions options) {
		return new ImageManipulationNode(client, cortexOptions, options);
	}

	private static ImageManipulationNodeOptions options() {
		return new ImageManipulationNodeOptions().setOutputFormat(OutputFormat.PNG).setOperations("RESIZE").setMaxLongEdge(40);
	}

	private NodeResultCreateRequest captureLedger() {
		ArgumentCaptor<NodeResultCreateRequest> captor = ArgumentCaptor.forClass(NodeResultCreateRequest.class);
		verify(client, times(1)).createAssetNodeResult(eq(assetUuid), captor.capture());
		return captor.getValue();
	}

	@Test
	void testSuccessRecordsExactlyOneLedgerRowWithNoResultRef() {
		NodeResult result = node(options()).process(NodeContext.create(media));
		assertThat(result).isSuccess();

		NodeResultCreateRequest ledger = captureLedger();
		assertEquals("image-manipulation", ledger.getNodeKind());
		assertEquals(ResultState.SUCCESS.name(), ledger.getState());
		assertNull(ledger.getResultRef(), "a ledger-only node must not claim Loom holds the bytes");
		assertTrue(ledger.getProducerVersion().startsWith(ImageManipulationNode.ALGORITHM_VERSION + ":"),
			"the producer version should identify the framing, got " + ledger.getProducerVersion());
	}

	@Test
	void testFailureRecordsAFailedLedgerRow() throws Exception {
		File broken = new File(tempDir, "broken.png");
		Files.writeString(broken.toPath(), "this is not a PNG");
		StubLoomMedia brokenMedia = new StubLoomMedia(broken.getAbsolutePath(), false, true, false, false);
		brokenMedia.setSHA512(HASH);

		NodeResult result = node(options()).process(NodeContext.create(brokenMedia));
		assertEquals(ResultState.FAILED, result.getState());

		NodeResultCreateRequest ledger = captureLedger();
		assertEquals(ResultState.FAILED.name(), ledger.getState());
		assertNull(ledger.getResultRef());
	}

	@Test
	void testASkipWritesNoLedgerRow() {
		NodeResult result = node(options().setOperations("SUBJECT_CROP").setSubjectFallback(SubjectFallback.SKIP))
			.process(NodeContext.create(media));

		assertEquals(ResultState.SKIPPED, result.getState());
		verify(client, times(0)).createAssetNodeResult(any(), any());
	}

	@Test
	void testACacheHitDoesNotRePersist() {
		ImageManipulationNode node = node(options());
		node.process(NodeContext.create(media));
		node.process(NodeContext.create(media));

		// A cache hit is SUCCESS with ResultOrigin.LOCAL - it must skip the recompute *and* the re-persist.
		verify(client, times(1)).createAssetNodeResult(any(), any());
	}

	@Test
	void testTheProducerVersionTracksTheFramingRatherThanOnlyTheNode() throws Exception {
		node(options().setTargetAspect("16:9").setOperations("ASPECT")).process(NodeContext.create(media));
		String wide = captureLedger().getProducerVersion();

		// A second node, a different framing: the ledger must be able to tell which one wrote a given asset.
		setupFresh();
		node(options().setTargetAspect("1:1").setOperations("ASPECT")).process(NodeContext.create(media));
		String square = captureLedger().getProducerVersion();

		org.junit.jupiter.api.Assertions.assertNotEquals(wide, square,
			"two framings produced the same producer version, so the ledger cannot tell them apart");
	}

	@SuppressWarnings("unchecked")
	private void setupFresh() throws Exception {
		client = mock(LoomHttpClient.class);
		AssetResponse asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);
		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);
	}
}
