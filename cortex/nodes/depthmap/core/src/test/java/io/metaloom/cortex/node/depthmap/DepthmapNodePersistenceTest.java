package io.metaloom.cortex.node.depthmap;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Verifies the ledger-only persistence contract of {@link DepthmapNode}: the produced bytes stay
 * in the local {@code depthmap_bin} cache and only an {@code asset_node_result} row reaches Loom,
 * stamped with the model that produced the map.
 *
 * <p>
 * The {@code producerVersion} assertion is the interesting one. {@code ImageGenNode} and
 * {@code TtsNode} pass null there, but which depth model produced a map materially changes its
 * numbers, so leaving it implicit would make two maps indistinguishable in the ledger.
 * </p>
 */
class DepthmapNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private DepthmapClient depthmapClient;
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

		depthmapClient = mock(DepthmapClient.class);

		File imageFile = new File(tempDir, "photo.jpg");
		ImageIO.write(new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB), "jpg", imageFile);
		media = new StubLoomMedia(imageFile.getAbsolutePath(), false, true, false, false);
		media.setSHA512(HASH);
	}

	private DepthmapNode node() {
		return new DepthmapNode(client, cortexOptions, new DepthmapNodeOptions(), depthmapClient);
	}

	@Test
	void testRecordsLedgerWithModelOnSuccess() throws Exception {
		when(depthmapClient.depth(any(), any(), any(), anyInt()))
			.thenReturn(DepthmapTestFixtures.response(DepthmapTestFixtures.gradientPng(32, 24), 32, 24));

		assertThat(node().process(NodeContext.create(media))).isSuccess();

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "depthmap".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& "COMPUTED".equals(r.getOrigin())
			// Ledger-only: the map bytes stay local, so there is no component to point at.
			&& r.getResultRef() == null
			// ...but the model that produced them is recorded.
			&& DepthmapTestFixtures.MODEL.equals(r.getProducerVersion())));

		// No typed component is written - this node has no queryable payload.
		verify(client, never()).createAssetJsonComp(any(), any());
	}

	@Test
	void testRecordsFailedLedgerWhenSidecarThrows() throws Exception {
		when(depthmapClient.depth(any(), any(), any(), anyInt())).thenThrow(new RuntimeException("sidecar down"));

		node().process(NodeContext.create(media));

		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "sidecar down".equals(r.getReason())));
	}

	@Test
	void testRecordsFailedLedgerWithModelWhenConventionIsRejected() throws Exception {
		when(depthmapClient.depth(any(), any(), any(), anyInt()))
			.thenReturn(DepthmapTestFixtures.response(DepthmapTestFixtures.gradientPng(32, 24), 32, 24)
				.put("convention", "SOMETHING_ELSE"));

		node().process(NodeContext.create(media));

		// The model is known by the time the convention check runs, so it still reaches the ledger -
		// which is exactly what makes a misconfigured sidecar traceable.
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState())
				&& DepthmapTestFixtures.MODEL.equals(r.getProducerVersion())
				&& r.getReason() != null && r.getReason().contains("SOMETHING_ELSE")));
	}
}
