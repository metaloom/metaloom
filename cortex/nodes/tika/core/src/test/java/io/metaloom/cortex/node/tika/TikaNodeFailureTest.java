package io.metaloom.cortex.node.tika;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.Map;
import java.util.UUID;

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
 * {@link TikaNode}'s failure path reports FAILED and names what went wrong.
 *
 * <p>
 * It ended in {@code ctx.failure("failed processing").next()} until 2026-08-18, which returned
 * SUCCESS and discarded even that message — so a document whose text was never extracted was
 * reported as extracted, with an empty result. The cause now also carries the exception message; the
 * bare constant said nothing an operator could act on.
 * </p>
 */
class TikaNodeFailureTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	@Test
	void testUnreadableSourceIsFailedAndSaysWhy() {
		// A path that exists — so the missing-file guard in AbstractMediaNode does not fire first — but
		// cannot be opened as a stream. That puts the failure inside the node's own catch block, which is
		// the line under test.
		File directory = new File(tempDir, "report.pdf");
		directory.mkdirs();

		StubLoomMedia media = new StubLoomMedia(directory.getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);

		TikaNode node = new TikaNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), new TikaNodeOptions());

		assertThat(node.process(NodeContext.create(media)))
			.isFailed()
			.hasMessageContaining("failed processing")
			.hasOutput(TikaNode.OUT_FLAGS, "FAILED")
			.hasNoOutput(TikaNode.OUT_CONTENT);
	}

	/**
	 * The failure reaches the ledger as a FAILED row. It used to stop at the task result, so the asset looked never-attempted rather than failed.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void testAFailureIsRecordedInTheLedger() throws Exception {
		UUID assetUuid = UUID.randomUUID();
		LoomHttpClient client = mock(LoomHttpClient.class);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(new AssetResponse().setUuid(assetUuid), 200, "OK", Map.of()));
		when(client.loadAsset(nullable(SHA512.class))).thenReturn(assetReq);
		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		File directory = new File(tempDir, "ledger.pdf");
		directory.mkdirs();
		StubLoomMedia media = new StubLoomMedia(directory.getAbsolutePath(), false, false, false, true);
		media.setSHA512(HASH);

		TikaNode node = new TikaNode(client, new CortexOptions().setMetaPath(tempDir.toPath()), new TikaNodeOptions());

		assertThat(node.process(NodeContext.create(media))).isFailed();
		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState()) && "tika".equals(r.getNodeKind())));
	}
}
