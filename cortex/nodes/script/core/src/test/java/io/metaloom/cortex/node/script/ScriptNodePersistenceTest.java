package io.metaloom.cortex.node.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.script.engine.ScriptEngine;
import io.metaloom.cortex.node.script.engine.js.GraalJsScriptEngine;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.impl.LoomClientResponseImpl;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompCreateRequest;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultCreateRequest;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompCreateRequest;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompListResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Verifies what {@link ScriptNode} writes to Loom.
 *
 * <p>
 * A real {@link LoomHttpClient} mock is used rather than a null client: passing null would make
 * every persistence path a silent no-op and this test would assert nothing.
 * </p>
 */
class ScriptNodePersistenceTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private final UUID assetUuid = UUID.randomUUID();
	private final UUID compUuid = UUID.randomUUID();

	private LoomHttpClient client;
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

		LoomClientRequest<JsonCompResponse> jsonCompReq = mock(LoomClientRequest.class);
		when(jsonCompReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new JsonCompResponse().setUuid(compUuid), 201, "Created", Map.of()));
		when(client.createAssetJsonComp(any(), any())).thenReturn(jsonCompReq);

		LoomClientRequest<SegmentCompListResponse> segmentReq = mock(LoomClientRequest.class);
		when(segmentReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new SegmentCompListResponse(), 201, "Created", Map.of()));
		when(client.createAssetSegmentComps(any(), any())).thenReturn(segmentReq);

		LoomClientRequest<NodeResultResponse> ledgerReq = mock(LoomClientRequest.class);
		when(ledgerReq.sync())
			.thenReturn(new LoomClientResponseImpl<>(new NodeResultResponse().setUuid(UUID.randomUUID()), 201, "Created", Map.of()));
		when(client.createAssetNodeResult(any(), any())).thenReturn(ledgerReq);

		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
		media.setSHA512(HASH);
	}

	private ScriptNode node(JsonObject def) {
		Map<String, Provider<ScriptEngine>> engines = Map.of(GraalJsScriptEngine.ID, GraalJsScriptEngine::new);
		ScriptNode node = new ScriptNode(client, cortexOptions, new ScriptNodeOptions(), engines);
		node.configure(def);
		return node;
	}

	private static JsonObject def(String nodeId, String script, JsonArray outputs) {
		return new JsonObject().put("id", nodeId).put("type", ScriptNode.KIND).put("script", script).put("outputs", outputs);
	}

	private static JsonArray outputs(String... keyTypePairs) {
		JsonArray array = new JsonArray();
		for (int i = 0; i < keyTypePairs.length; i += 2) {
			array.add(new JsonObject().put("key", keyTypePairs[i]).put("type", keyTypePairs[i + 1]));
		}
		return array;
	}

	private NodeContext<LoomMedia> ctx() {
		return NodeContext.create(media, Map.of());
	}

	@Test
	void shouldWriteOneJsonCompVariantPerNodeIdAndALedgerEntry() {
		ScriptNode node = node(def("chapter-marks", """
			out.text('caption', 'a red car');
			out.integer('count', 2);
			""", outputs("caption", "TEXT", "count", "INTEGER")));

		assertEquals(ResultState.SUCCESS, node.process(ctx()).getState());

		verify(client).createAssetJsonComp(eq(assetUuid), argThat((JsonCompCreateRequest r) -> "script".equals(r.getNodeKind())
			&& ScriptNode.SCHEMA_TYPE.equals(r.getSchemaType())
			// The node id discriminates several script nodes on one asset.
			&& "chapter-marks".equals(r.getVariant())
			&& r.getProducerVersion().startsWith("js:")
			&& "a red car".equals(r.getData().getString("caption"))
			&& r.getData().getLong("count") == 2L));

		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "script".equals(r.getNodeKind())
			&& "SUCCESS".equals(r.getState())
			&& r.getProducerVersion().startsWith("js:")
			&& r.getResultRef() != null
			&& "asset_json_comp".equals(r.getResultRef().getString("table"))
			&& r.getResultRef().getJsonArray("uuids").contains(compUuid.toString())));
	}

	@Test
	void shouldWriteTimeframesAsSegmentComps() {
		ScriptNode node = node(def("chapter-marks", """
			out.timeframes('chapter_frames', [
			  { startMs: 0,    endMs: 1500, label: 'intro' },
			  { startMs: 1500, endMs: 9000, label: 'body' }
			]);
			""", outputs("chapter_frames", "TIMEFRAMES")));

		assertEquals(ResultState.SUCCESS, node.process(ctx()).getState());

		verify(client).createAssetSegmentComps(eq(assetUuid), argThat((SegmentCompCreateRequest r) ->
			// Scoped per node id: the replace set is keyed by node_kind, so a plain "script" kind
			// would let a second script node delete this one's segments.
			"script:chapter-marks".equals(r.getNodeKind())
			// segment_type is CHECK-constrained in the schema, so it is the declared segmentType
			// (defaulting to CHAPTER) rather than the output key.
			&& "CHAPTER".equals(r.getSegmentType())
			&& r.getSegments().size() == 2
			&& r.getSegments().get(0).getSeq() == 0
			&& r.getSegments().get(0).getTimeFrom() == 0L
			&& r.getSegments().get(0).getTimeTo() == 1500L
			&& "intro".equals(r.getSegments().get(0).getTitle())
			&& r.getSegments().get(1).getSeq() == 1
			&& r.getSegments().get(1).getTimeTo() == 9000L));
	}

	@Test
	void shouldNotWriteAJsonCompWhenEveryOutputHasItsOwnLandingZone() {
		// A timeframes-only script has nothing left for the generic JSON component; writing an
		// empty one would be a row that says nothing.
		ScriptNode node = node(def("marks", "out.timeframes('frames', [{ startMs: 0, endMs: 10 }]);",
			outputs("frames", "TIMEFRAMES")));

		node.process(ctx());

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetSegmentComps(any(), any());
	}

	@Test
	void shouldRecordAFailedLedgerEntryAndWriteNoComponentWhenTheScriptFails() {
		ScriptNode node = node(def("marks", "ctx.fail('bad upstream data');", outputs("caption", "TEXT")));

		assertEquals(ResultState.FAILED, node.process(ctx()).getState());

		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "FAILED".equals(r.getState())
			&& "bad upstream data".equals(r.getReason())
			&& r.getResultRef() == null));
	}

	@Test
	void shouldNotPersistImagesBeyondTheLedger() {
		String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
		ScriptNode node = node(def("thumbs", "out.image('shot', params.png);", outputs("shot", "IMAGE"))
			.put("params", new JsonObject().put("png", png)));

		assertEquals(ResultState.SUCCESS, node.process(ctx()).getState());

		// The bytes stay in the local script_bin cache; there is no byte-ingest endpoint for
		// produced media, so only the ledger records that they exist.
		verify(client, never()).createAssetJsonComp(any(), any());
		verify(client).createAssetNodeResult(eq(assetUuid), argThat((NodeResultCreateRequest r) -> "SUCCESS".equals(r.getState())));
	}
}
