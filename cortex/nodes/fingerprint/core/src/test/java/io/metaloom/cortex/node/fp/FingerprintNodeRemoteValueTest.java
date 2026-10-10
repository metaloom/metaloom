package io.metaloom.cortex.node.fp;

import static io.metaloom.cortex.media.test.assertj.NodeAssertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.common.node.media.AbstractMediaTest;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.info.FingerprintInfo;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompCreateRequest;
import io.metaloom.loom.rest.model.fingerprintcomp.FingerprintCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.metaloom.video4j.Video4j;

/**
 * How {@link FingerprintNode} treats the fingerprint Loom already knows about an asset.
 *
 * <p>
 * The asset's {@code fingerprint} block can exist without a v1 value - another algorithm wrote the
 * block, or the row was created ahead of the computation. The node used to test only for the block
 * and re-emit whatever v1 held: a {@code null}, reported as a {@code REMOTE} success. Nothing landed
 * on the port, the computation was skipped for good, and a downstream dedup node starved on a
 * "successful" upstream. The contract pinned here is that only a <em>value</em> is replayed from
 * Loom; a missing one is computed.
 * </p>
 */
class FingerprintNodeRemoteValueTest extends AbstractMediaTest {

	static {
		// The fingerprint is computed by opening the file through video4j.
		Video4j.init();
	}

	private final UUID assetUuid = UUID.randomUUID();

	private LoomHttpClient client;
	private AssetResponse asset;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setupClient() throws Exception {
		client = mock(LoomHttpClient.class);

		asset = new AssetResponse().setUuid(assetUuid);
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(any(SHA512.class))).thenReturn(assetReq);

		LoomClientRequest<FingerprintCompResponse> compReq = mock(LoomClientRequest.class);
		when(compReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new FingerprintCompResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetFingerprintComp(any(), any())).thenReturn(compReq);

		LoomClientRequest<NodeResultResponse> nodeResultReq = mock(LoomClientRequest.class);
		when(nodeResultReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(nodeResultReq);
	}

	private FingerprintNode node() {
		return new FingerprintNode(client, options(), new FingerprintNodeOptions());
	}

	/**
	 * A fingerprint block whose v1 is null is not a remote answer. The node has to compute the
	 * fingerprint, emit the real value and persist it - exactly as if Loom had known nothing.
	 */
	@Test
	void testNullRemoteValueIsComputedNotReplayedAsRemote() throws Exception {
		asset.setFingerprint(new FingerprintInfo());

		NodeResult result = node().process(NodeContext.create(mediaVideo1()));

		assertThat(result).isSuccess().hasOutput(FingerprintNode.OUT_FINGERPRINT, sampleVideoFingerprint());
		assertEquals(ResultOrigin.COMPUTED, result.getOrigin(), "A missing remote value must be computed, not replayed as REMOTE");

		// The asset was there - so the only way the computation ran is that the null was not mistaken for a value.
		verify(client).loadAsset(any(SHA512.class));
		verify(client).createAssetFingerprintComp(eq(assetUuid),
			argThat((FingerprintCompCreateRequest r) -> sampleVideoFingerprint().equals(r.getFingerprint())));
		verify(client).createAssetNodeResult(eq(assetUuid),
			argThat((NodeResultCreateRequest r) -> "SUCCESS".equals(r.getState()) && "COMPUTED".equals(r.getOrigin())));
	}

	/** The contrast: a block that does carry a value is replayed from Loom and nothing is recomputed or rewritten. */
	@Test
	void testPresentRemoteValueIsReplayedAsRemote() throws Exception {
		asset.setFingerprint(new FingerprintInfo().setFingerprintV1("cafebabe"));

		NodeResult result = node().process(NodeContext.create(mediaVideo1()));

		assertThat(result).isSuccess().hasOutput(FingerprintNode.OUT_FINGERPRINT, "cafebabe");
		assertEquals(ResultOrigin.REMOTE, result.getOrigin());
		verify(client, never()).createAssetFingerprintComp(any(), any());
		verify(client, never()).createAssetNodeResult(any(), any());
	}
}
