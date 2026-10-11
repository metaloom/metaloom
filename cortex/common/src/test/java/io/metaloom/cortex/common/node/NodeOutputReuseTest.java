package io.metaloom.cortex.common.node;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.OutputPort;
import io.metaloom.cortex.api.node.ResultOrigin;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.node.context.impl.NodeContextImpl;
import io.metaloom.cortex.api.option.node.AbstractNodeOptions;
import io.metaloom.cortex.common.node.reuse.NodeOutputReuse;
import io.metaloom.cortex.common.node.reuse.ReusePolicy;
import io.metaloom.loom.client.common.LoomClient;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.pipeline.model.Origin;
import io.metaloom.loom.pipeline.model.PortPayload;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputCreateRequest;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputLookupResponse;
import io.metaloom.loom.rest.model.nodeoutput.NodeOutputResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

/**
 * Node output reuse in {@link AbstractMediaNode}: a node with a stored output for the same asset, options and inputs replays it instead of computing,
 * and {@code recompute} forces the work.
 */
public class NodeOutputReuseTest {

	private static final SHA512 SHA = SHA512.fromString("e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	public static class TestOptions extends AbstractNodeOptions<TestOptions> {

		private String model = "base";

		public String getModel() {
			return model;
		}

		public TestOptions setModel(String model) {
			this.model = model;
			return this;
		}

		@Override
		protected TestOptions self() {
			return this;
		}
	}

	/** Emits a text derived from its input and the model, an artifact path, and echoes the item's own media path. */
	static class CountingNode extends AbstractMediaNode<TestOptions> {

		static final OutputPort<String> OUT_TEXT = OutputPort.one("text", ContentTypeRegistry.TEXT_PLAIN, String.class);
		static final OutputPort<String> OUT_FILE = OutputPort.one("file", ContentTypeRegistry.ARTIFACT_FILE, String.class);
		static final OutputPort<String> OUT_MEDIA = OutputPort.one("source", ContentTypeRegistry.MEDIA_IMAGE, String.class);

		int computations;
		ReusePolicy policy = ReusePolicy.SHARED;
		Path artifact;
		Map<String, Object> refresh = Map.of();

		CountingNode(LoomClient client, TestOptions options) {
			super(client, null, options);
		}

		@Override
		public String name() {
			return "counting";
		}

		@Override
		protected ReusePolicy reusePolicy() {
			return policy;
		}

		@Override
		protected Map<String, Object> refreshOnReuse(AssetResponse asset) {
			return refresh;
		}

		@Override
		protected boolean isProcessable(NodeContext<LoomMedia> ctx) {
			return true;
		}

		@Override
		protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
			computations++;
			PortPayload text = ctx.inputPorts().get("text");
			String input = text == null ? "" : String.valueOf(text.single());
			ctx.output(OUT_TEXT, options().getModel() + ":" + input);
			if (artifact != null) {
				ctx.output(OUT_FILE, artifact.toString());
			}
			ctx.output(OUT_MEDIA, ctx.media().absolutePath());
			return ctx.origin(ResultOrigin.COMPUTED).next();
		}
	}

	/** Loom's reuse store, in memory: what createAssetNodeOutput stored is what lookupNodeOutput answers. */
	private final Map<String, NodeOutputCreateRequest> store = new HashMap<>();

	private AssetResponse asset;

	private LoomHttpClient client;

	@TempDir
	Path tmp;

	@BeforeEach
	@SuppressWarnings("unchecked")
	public void setup() throws Exception {
		store.clear();
		asset = new AssetResponse();
		asset.setUuid(UUID.randomUUID());
		client = mock(LoomHttpClient.class);
		when(client.lookupNodeOutput(any(SHA512.class), any(), any())).thenAnswer(invocation -> {
			String kind = invocation.getArgument(1);
			String key = invocation.getArgument(2);
			NodeOutputCreateRequest stored = store.get(kind + "/" + key);
			NodeOutputLookupResponse response = new NodeOutputLookupResponse().setAsset(asset);
			if (stored != null) {
				response.setOutput(new NodeOutputResponse()
					.setUuid(UUID.randomUUID().toString())
					.setNodeKind(stored.getNodeKind())
					.setReuseKey(stored.getReuseKey())
					.setState(stored.getState())
					.setOutputs(new JsonObject(stored.getOutputs().encode())));
			}
			LoomClientRequest<NodeOutputLookupResponse> req = mock(LoomClientRequest.class);
			when(req.sync()).thenReturn(new LoomClientResponseImpl<>(response, 200, "OK", Map.of()));
			return req;
		});
		when(client.createAssetNodeOutput(any(UUID.class), any())).thenAnswer(invocation -> {
			NodeOutputCreateRequest request = invocation.getArgument(1);
			store.put(request.getNodeKind() + "/" + request.getReuseKey(), request);
			LoomClientRequest<NodeOutputResponse> req = mock(LoomClientRequest.class);
			when(req.sync()).thenReturn(new LoomClientResponseImpl<>(new NodeOutputResponse(), 201, "Created", Map.of()));
			return req;
		});
		LoomClientRequest<AssetResponse> assetReq = mock(LoomClientRequest.class);
		when(assetReq.sync()).thenReturn(new LoomClientResponseImpl<>(asset, 200, "OK", Map.of()));
		when(client.loadAsset(any(SHA512.class))).thenReturn(assetReq);
		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync()).thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse(), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(UUID.class), any())).thenReturn(ledgerReq);
	}

	private LoomMedia media(String path) {
		LoomMedia media = mock(LoomMedia.class);
		when(media.exists()).thenReturn(true);
		when(media.absolutePath()).thenReturn(path);
		when(media.getSHA512()).thenReturn(SHA);
		return media;
	}

	private static NodeInputs text(String value) {
		return NodeInputs.of(Map.of("text", PortPayload.one(ContentTypeRegistry.TEXT_PLAIN, new Origin("item-1", 0, 1), value)));
	}

	private NodeResult run(CountingNode node, String mediaPath, NodeInputs inputs) throws Exception {
		return node.process(new NodeContextImpl<>(media(mediaPath), inputs));
	}

	@Test
	public void testSecondRunReplaysInsteadOfComputing() throws Exception {
		CountingNode first = new CountingNode(client, new TestOptions());
		NodeResult computed = run(first, "/worker-a/clip.jpg", text("hello"));
		assertEquals(1, first.computations);
		assertEquals(1, store.size(), "The computed output is stored for the next run");

		// A fresh instance, as the runtime builds one per task - in another pipeline, on another worker path.
		CountingNode second = new CountingNode(client, new TestOptions());
		NodeResult replayed = run(second, "/worker-b/clip.jpg", text("hello"));

		assertEquals(0, second.computations, "The stored output is replayed");
		assertEquals(ResultState.SUCCESS, replayed.getState());
		assertEquals(ResultOrigin.REMOTE, replayed.getOrigin(), "A replay is reported as fetched from Loom");
		assertEquals(computed.getOutputs().get("text").single(), replayed.getOutputs().get("text").single());
		assertEquals("/worker-b/clip.jpg", replayed.getOutputs().get("source").single(),
			"The item's own media path is the current worker's, not the one stored");
		verify(client, never()).loadAsset(any(SHA512.class));

		ArgumentCaptor<NodeResultCreateRequest> ledger = ArgumentCaptor.forClass(NodeResultCreateRequest.class);
		verify(client).createAssetNodeResult(any(UUID.class), ledger.capture());
		assertEquals("REMOTE", ledger.getValue().getOrigin(), "The ledger records that this execution reused rather than computed");
		assertEquals("SUCCESS", ledger.getValue().getState());
	}

	@Test
	public void testRecomputeOnTheDefinitionForcesTheWork() throws Exception {
		run(new CountingNode(client, new TestOptions()), "/m.jpg", text("hello"));

		CountingNode forced = new CountingNode(client, new TestOptions());
		forced.useDefinition(new JsonObject().put("id", "n1").put("type", "counting").put("recompute", true));
		NodeResult result = run(forced, "/m.jpg", text("hello"));

		assertEquals(1, forced.computations, "recompute ignores the stored output");
		assertEquals(ResultOrigin.COMPUTED, result.getOrigin());
		assertEquals(1, store.size(), "The fresh output replaces the stored one under the same key");
		verify(client, never()).lookupNodeOutput(any(UUID.class), any(), any());
	}

	@Test
	public void testRecomputeOnTheOptionsForcesTheWork() throws Exception {
		run(new CountingNode(client, new TestOptions()), "/m.jpg", text("hello"));
		CountingNode forced = new CountingNode(client, new TestOptions().setRecompute(true));
		run(forced, "/m.jpg", text("hello"));
		assertEquals(1, forced.computations);
		assertEquals(1, store.size(), "recompute is not part of the key, so the plain run finds the refreshed output");
	}

	@Test
	public void testDifferentOptionsOrInputsAreDifferentQuestions() throws Exception {
		run(new CountingNode(client, new TestOptions()), "/m.jpg", text("hello"));

		CountingNode otherModel = new CountingNode(client, new TestOptions().setModel("large"));
		assertEquals("large:hello", run(otherModel, "/m.jpg", text("hello")).getOutputs().get("text").single());
		assertEquals(1, otherModel.computations, "Another option value is another answer");

		CountingNode otherInput = new CountingNode(client, new TestOptions());
		assertEquals("base:bye", run(otherInput, "/m.jpg", text("bye")).getOutputs().get("text").single());
		assertEquals(1, otherInput.computations, "Another input value is another answer");
		assertEquals(3, store.size());
	}

	@Test
	public void testNeverPolicyNeitherLooksUpNorStores() throws Exception {
		CountingNode node = new CountingNode(client, new TestOptions());
		node.policy = ReusePolicy.NEVER;
		run(node, "/m.jpg", text("hello"));
		run(node, "/m.jpg", text("hello"));
		assertEquals(2, node.computations);
		assertTrue(store.isEmpty());
		verify(client, never()).lookupNodeOutput(any(SHA512.class), any(), any());
	}

	@Test
	public void testPerNodeIdPolicyScopesTheKey() throws Exception {
		CountingNode a = new CountingNode(client, new TestOptions()) {
			@Override
			protected String nodeId() {
				return "pipeline-a-node";
			}
		};
		a.policy = ReusePolicy.PER_NODE_ID;
		run(a, "/m.jpg", text("hello"));

		CountingNode b = new CountingNode(client, new TestOptions()) {
			@Override
			protected String nodeId() {
				return "pipeline-b-node";
			}
		};
		b.policy = ReusePolicy.PER_NODE_ID;
		run(b, "/m.jpg", text("hello"));
		assertEquals(1, b.computations, "Another node id does not see the first one's output");
	}

	/** Produced bytes live on the worker that made them; a stored path that is gone here is a miss, not a broken downstream input. */
	@Test
	public void testMissingProducedFileIsAMiss() throws Exception {
		Path file = Files.writeString(tmp.resolve("artifact.bin"), "bytes");
		CountingNode first = new CountingNode(client, new TestOptions());
		first.artifact = file;
		run(first, "/m.jpg", text("hello"));

		CountingNode present = new CountingNode(client, new TestOptions());
		present.artifact = file;
		run(present, "/m.jpg", text("hello"));
		assertEquals(0, present.computations, "The file is still there: replay");

		Files.delete(file);
		CountingNode gone = new CountingNode(client, new TestOptions());
		gone.artifact = file;
		run(gone, "/m.jpg", text("hello"));
		assertEquals(1, gone.computations, "The file is gone: compute again");
	}

	@Test
	public void testRefreshReplacesStateDependentPorts() throws Exception {
		run(new CountingNode(client, new TestOptions()), "/m.jpg", text("hello"));
		CountingNode node = new CountingNode(client, new TestOptions());
		node.refresh = Map.of("text", "fresh");
		assertEquals("fresh", run(node, "/m.jpg", text("hello")).getOutputs().get("text").single());
		assertEquals(0, node.computations);
	}

	@Test
	public void testFailureIsNotStored() throws Exception {
		CountingNode failing = new CountingNode(client, new TestOptions()) {
			@Override
			protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
				computations++;
				return ctx.failure("sidecar down").abort();
			}
		};
		run(failing, "/m.jpg", text("hello"));
		run(failing, "/m.jpg", text("hello"));
		assertEquals(2, failing.computations, "A failure is never replayed");
		assertTrue(store.isEmpty());
	}

	/**
	 * A node that computed but could not write its typed result reports SUCCESS and records FAILED in the ledger. Storing that output would make the
	 * missing result permanent - the next run would replay instead of writing - so it is not stored.
	 */
	@Test
	public void testUnpersistedResultIsNotStored() throws Exception {
		CountingNode node = new CountingNode(client, new TestOptions()) {
			@Override
			protected NodeResult compute(NodeContext<LoomMedia> ctx, AssetResponse asset) {
				NodeResult result = super.compute(ctx, asset);
				recordNodeResult(asset, ctx, ResultState.FAILED, "transcript write failed", "v1", null);
				return result;
			}
		};
		assertEquals(ResultState.SUCCESS, run(node, "/m.jpg", text("hello")).getState());
		assertTrue(store.isEmpty(), "Nothing is stored, so the next run computes and writes again");
	}

	@Test
	public void testOfflineComputesWithoutReuse() throws Exception {
		CountingNode node = new CountingNode(null, new TestOptions());
		NodeResult result = run(node, "/m.jpg", text("hello"));
		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals(1, node.computations);
	}

	/** A Loom that cannot answer the lookup must cost a computation, never the item. */
	@Test
	public void testFailingLookupFallsBackToComputing() throws Exception {
		when(client.lookupNodeOutput(any(SHA512.class), any(), any())).thenThrow(new RuntimeException("connection refused"));
		CountingNode node = new CountingNode(client, new TestOptions());
		NodeResult result = run(node, "/m.jpg", text("hello"));
		assertEquals(ResultState.SUCCESS, result.getState());
		assertEquals(1, node.computations);
		verify(client).loadAsset(any(SHA512.class));
	}

	// --- the key itself ----------------------------------------------------------------------------------------------------------------------

	/**
	 * The key is what makes two runs "the same question". It must not change with things that do not change the answer - option order, the origin
	 * stamps of the run that delivered an input, the reuse and enable switches - and must change with everything that does.
	 */
	@Test
	public void testKeyCoversWhatChangesTheAnswerAndNothingElse() {
		String signature = NodeOutputReuse.outputSignature(CountingNode.class);
		Map<String, PortPayload> inputs = Map.of("text", PortPayload.one(ContentTypeRegistry.TEXT_PLAIN, new Origin("item-1", 0, 1), "hello"));
		Map<String, PortPayload> sameValueOtherRun = Map.of("text", PortPayload.one(ContentTypeRegistry.TEXT_PLAIN, new Origin("item-9", 3, 4), "hello"));
		String base = NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions(), null, null, inputs, "/m.jpg");

		assertEquals(64, base.length(), "A hex SHA-256");
		assertEquals(base, NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions(), null, null, sameValueOtherRun, "/m.jpg"),
			"Origins belong to the run, not to the question");
		assertEquals(base, NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions().setRecompute(true), null, null, inputs, "/m.jpg"),
			"recompute is not part of the question");
		assertNotEquals(base, NodeOutputReuse.reuseKey("counting", "2", signature, new TestOptions(), null, null, inputs, "/m.jpg"),
			"A bumped reuse version invalidates");
		assertNotEquals(base, NodeOutputReuse.reuseKey("counting", "1", signature + ";x", new TestOptions(), null, null, inputs, "/m.jpg"),
			"A changed port shape invalidates");
		assertNotEquals(base, NodeOutputReuse.reuseKey("other", "1", signature, new TestOptions(), null, null, inputs, "/m.jpg"));

		JsonObject definitionA = new JsonObject().put("id", "a").put("type", "counting").put("threshold", 0.5).put("labels", List.of("x", "y"));
		JsonObject definitionB = new JsonObject().put("labels", List.of("x", "y")).put("threshold", 0.5).put("type", "counting").put("id", "b")
			.put("recompute", false);
		assertEquals(NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions(), definitionA, null, inputs, "/m.jpg"),
			NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions(), definitionB, null, inputs, "/m.jpg"),
			"Key order, the graph id and the recompute switch of a definition do not matter");
		assertNotEquals(NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions(), definitionA, null, inputs, "/m.jpg"),
			NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions(), definitionA.copy().put("threshold", 0.6), null, inputs, "/m.jpg"));
	}

	/** The item's own media is the asset the store is keyed by already; its worker-local path must not split the key. */
	@Test
	public void testItemMediaPathIsNotPartOfTheKey() {
		String signature = NodeOutputReuse.outputSignature(CountingNode.class);
		String onA = NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions(), null, null,
			Map.of("media", PortPayload.one(ContentTypeRegistry.MEDIA_IMAGE, new Origin("i", 0, 1), "/worker-a/clip.jpg")), "/worker-a/clip.jpg");
		String onB = NodeOutputReuse.reuseKey("counting", "1", signature, new TestOptions(), null, null,
			Map.of("media", PortPayload.one(ContentTypeRegistry.MEDIA_IMAGE, new Origin("i", 0, 1), "/worker-b/clip.jpg")), "/worker-b/clip.jpg");
		assertEquals(onA, onB);
	}

	@Test
	public void testEncodeAndDecodeRoundTrip() {
		CountingNode node = new CountingNode(client, new TestOptions());
		NodeContextImpl<LoomMedia> ctx = new NodeContextImpl<>(media("/m.jpg"), NodeInputs.empty());
		ctx.output(CountingNode.OUT_TEXT, "hello");
		ctx.output(CountingNode.OUT_MEDIA, "/m.jpg");
		JsonObject stored = NodeOutputReuse.encode(ctx.outputs(), "/m.jpg");
		assertNotNull(stored);
		assertEquals(NodeOutputReuse.MEDIA_PLACEHOLDER, stored.getJsonObject("source").getJsonArray("values").getString(0));

		var decoded = NodeOutputReuse.decode(new JsonObject(stored.encode()), "/elsewhere/m.jpg");
		assertNotNull(decoded);
		assertEquals("hello", decoded.get("text").single());
		assertEquals("/elsewhere/m.jpg", decoded.get("source").single());
		assertEquals(ContentTypeRegistry.TEXT_PLAIN, decoded.get("text").port().contentType());
		assertNull(NodeOutputReuse.decode(new JsonObject().put("text", "not a port"), "/m.jpg"), "A malformed stored form is a miss");
		assertNotNull(node);
	}
}
