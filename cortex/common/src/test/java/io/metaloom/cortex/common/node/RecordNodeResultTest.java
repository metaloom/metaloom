package io.metaloom.cortex.common.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.context.impl.NodeContextImpl;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;

/**
 * The provenance contract of {@link AbstractMediaNode#recordNodeResult}: the ledger row must carry
 * the origin the node actually determined for <em>this</em> item and the identity of the execution
 * that wrote it.
 *
 * <p>
 * Until 2026-08-20 the origin was the hardcoded constant {@code COMPUTED}, which made a cache
 * replay indistinguishable from real work even though the node had computed the real answer for its
 * own skip decision — and no run reference was sent at all, so a row could never be joined to the
 * run that produced it.
 * </p>
 */
public class RecordNodeResultTest {

	private static class DummyOptions extends AbstractNodeOptions<DummyOptions> {
		@Override
		protected DummyOptions self() {
			return this;
		}
	}

	private static class DummyNode extends AbstractMediaNode<DummyOptions> {

		DummyNode(io.metaloom.loom.client.common.LoomClient client) {
			super(client, null, new DummyOptions());
		}

		@Override
		public String name() {
			return "dummy";
		}

		@Override
		protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
			return true;
		}

		@Override
		protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
			return ctx.next();
		}
	}

	@SuppressWarnings("unchecked")
	private LoomHttpClient mockClient() throws Exception {
		LoomHttpClient client = mock(LoomHttpClient.class);
		LoomClientRequest<NodeResultResponse> request = mock(LoomClientRequest.class);
		when(request.sync()).thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse(), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(UUID.class), any())).thenReturn(request);
		return client;
	}

	private AssetResponse asset() {
		AssetResponse asset = new AssetResponse();
		asset.setUuid(UUID.randomUUID());
		return asset;
	}

	/**
	 * A LOCAL-origin item — a cache replay — must record {@code LOCAL}, and an execution inside a
	 * pipeline run must record the run and task it belongs to.
	 */
	@Test
	public void testLocalOriginAndRunIdentityReachTheLedger() throws Exception {
		LoomHttpClient client = mockClient();
		DummyNode node = new DummyNode(client);

		UUID runUuid = UUID.randomUUID();
		UUID taskUuid = UUID.randomUUID();
		NodeContextImpl<LoomMedia> ctx = new NodeContextImpl<>((LoomMedia) null, NodeInputs.empty().withExecution(runUuid, taskUuid));
		ctx.origin(ResultOrigin.LOCAL);

		node.recordNodeResult(asset(), ctx, ResultState.SUCCESS, null, "v1", null);

		ArgumentCaptor<NodeResultCreateRequest> captor = ArgumentCaptor.forClass(NodeResultCreateRequest.class);
		verify(client).createAssetNodeResult(any(UUID.class), captor.capture());
		NodeResultCreateRequest ledger = captor.getValue();
		assertEquals("LOCAL", ledger.getOrigin(), "The origin the node determined must reach the ledger");
		assertEquals(runUuid.toString(), ledger.getRunUuid());
		assertEquals(taskUuid.toString(), ledger.getTaskUuid());
	}

	/**
	 * A recomputed item that never called {@code ctx.origin(...)} keeps the historical default, and
	 * an execution outside a pipeline run references no run.
	 */
	@Test
	public void testRecomputedItemDefaultsToComputedWithoutRunReference() throws Exception {
		LoomHttpClient client = mockClient();
		DummyNode node = new DummyNode(client);

		NodeContextImpl<LoomMedia> ctx = new NodeContextImpl<>((LoomMedia) null, NodeInputs.empty());

		node.recordNodeResult(asset(), ctx, ResultState.SUCCESS, null, "v1", null);

		ArgumentCaptor<NodeResultCreateRequest> captor = ArgumentCaptor.forClass(NodeResultCreateRequest.class);
		verify(client).createAssetNodeResult(any(UUID.class), captor.capture());
		NodeResultCreateRequest ledger = captor.getValue();
		assertEquals("COMPUTED", ledger.getOrigin());
		assertNull(ledger.getRunUuid());
		assertNull(ledger.getTaskUuid());
	}

	/** An explicitly recomputed item records {@code COMPUTED} even when the node reports origins. */
	@Test
	public void testComputedOriginIsRecordedAsComputed() throws Exception {
		LoomHttpClient client = mockClient();
		DummyNode node = new DummyNode(client);

		NodeContextImpl<LoomMedia> ctx = new NodeContextImpl<>((LoomMedia) null, NodeInputs.empty());
		ctx.origin(ResultOrigin.COMPUTED);

		node.recordNodeResult(asset(), ctx, ResultState.SUCCESS, null, "v1", null);

		ArgumentCaptor<NodeResultCreateRequest> captor = ArgumentCaptor.forClass(NodeResultCreateRequest.class);
		verify(client).createAssetNodeResult(any(UUID.class), captor.capture());
		assertEquals("COMPUTED", captor.getValue().getOrigin());
	}
}
