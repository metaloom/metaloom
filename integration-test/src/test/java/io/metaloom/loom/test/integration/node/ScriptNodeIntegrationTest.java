package io.metaloom.loom.test.integration.node;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.script.ScriptNode;
import io.metaloom.cortex.node.script.ScriptNodeOptions;
import io.metaloom.cortex.node.script.engine.ScriptEngine;
import io.metaloom.cortex.node.script.engine.js.GraalJsScriptEngine;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.jsoncomp.JsonCompResponse;
import io.metaloom.loom.rest.model.noderesult.NodeResultResponse;
import io.metaloom.loom.rest.model.segmentcomp.SegmentCompResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Integration test for {@code ScriptNode} against a real in-process Loom + pooled Postgres.
 *
 * <p>
 * Nothing is stubbed here - unlike the model-backed nodes, this node has no external runtime, so
 * the real GraalJS engine, the real {@code LoomHttpClient} and the real REST read-back are all
 * exercised. What it proves is the part unit tests cannot: a script's declared outputs land in the
 * right tables and come back out through the API.
 * </p>
 */
public class ScriptNodeIntegrationTest extends AbstractNodeIntegrationTest {

	private static final String SCRIPT = """
		// One upstream text in; a scalar, a list and a timeline out. Wired text arrives as
		// data.text - the old upstream['<nodeId>']['<outputKey>'] bag is gone, because it broke
		// whenever the pipeline author renamed the node it named.
		const transcript = JSON.parse(data.text);
		const frames = transcript.segments
		  .filter(s => new RegExp(params.pattern, 'i').test(s.text))
		  .map(s => ({ startMs: Math.round(s.start * 1000), endMs: Math.round(s.end * 1000), label: s.text }));
		out.timeframes('chapter_frames', frames);
		out.list('chapter_titles', frames.map(f => f.label));
		out.integer('chapter_count', frames.length);
		log.info('derived ' + frames.length + ' chapter marks');
		""";

	private static final String TRANSCRIPT = new JsonObject()
		.put("segments", new JsonArray()
			.add(new JsonObject().put("start", 0.0).put("end", 1.0).put("text", "welcome"))
			.add(new JsonObject().put("start", 2.0).put("end", 3.5).put("text", "Chapter one"))
			.add(new JsonObject().put("start", 9.0).put("end", 10.25).put("text", "Chapter two")))
		.encode();

	private static JsonObject nodeDef(String nodeId) {
		return new JsonObject()
			.put("id", nodeId)
			.put("type", ScriptNode.KIND)
			.put("script", SCRIPT)
			.put("params", new JsonObject().put("pattern", "chapter"))
			.put("outputs", new JsonArray()
				.add(new JsonObject().put("key", "chapter_frames").put("type", "TIMEFRAMES"))
				.add(new JsonObject().put("key", "chapter_titles").put("type", "TEXT_LIST"))
				.add(new JsonObject().put("key", "chapter_count").put("type", "INTEGER")));
	}

	private static ScriptNode node(io.metaloom.loom.client.http.LoomHttpClient client, CortexOptions options, JsonObject def) {
		Map<String, Provider<ScriptEngine>> engines = Map.of(GraalJsScriptEngine.ID, GraalJsScriptEngine::new);
		ScriptNode node = new ScriptNode(client, options, new ScriptNodeOptions(), engines);
		node.configure(def);
		return node;
	}

	@Test
	public void testScriptPersistsJsonCompSegmentsAndLedger() throws Exception {
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, video1(), "video/mp4");

			Path metaPath = Files.createTempDirectory("node-it-script");
			CortexOptions options = new CortexOptions().setMetaPath(metaPath);
			ScriptNode node = node(client, options, nodeDef("chapter-marks"));

			NodeContext<LoomMedia> ctx = NodeContext.create(media(video1()),
				NodeInputs.builder()
					.input(ScriptNode.IN_TEXT, TRANSCRIPT)
					.build());
			NodeResult result = node.process(ctx);

			assertThat(result.getState()).as(result.getMessage()).isEqualTo(ResultState.SUCCESS);
			// getOutputs() is keyed by port id and holds a PortOutput, not the bare value.
			assertThat(result.getOutputs().get("chapter_count").single()).isEqualTo(2L);

			// The scalar/list outputs land in one JSON component, keyed by node id so that several
			// script nodes can coexist on one asset.
			JsonCompResponse comp = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
				.filter(c -> ScriptNode.SCHEMA_TYPE.equals(c.getSchemaType()))
				.filter(c -> "chapter-marks".equals(c.getVariant()))
				.findFirst().orElse(null);
			assertThat(comp).as("script JSON component must be readable via REST").isNotNull();
			assertThat(comp.getData().getLong("chapter_count")).isEqualTo(2L);
			assertThat(comp.getData().getJsonArray("chapter_titles").getList())
				.containsExactly("Chapter one", "Chapter two");
			assertThat(comp.getProducerVersion()).as("the ledger records which script produced this").startsWith("js:");
			// Timeframes have their own table; duplicating them in the JSON blob would leave two
			// copies to keep in step.
			assertThat(comp.getData().containsKey("chapter_frames")).isFalse();

			// The timeframes must be real segment rows, not an opaque blob.
			// segment_type is CHECK-constrained by the schema, so it is the declared segmentType
			// (CHAPTER by default) rather than the output key; the rows are scoped per node id.
			List<SegmentCompResponse> segments = client.listAssetSegmentComps(asset.getUuid()).sync().body().getData().stream()
				.filter(s -> "CHAPTER".equals(s.getSegmentType()))
				.filter(s -> "script:chapter-marks".equals(s.getNodeKind()))
				.sorted(java.util.Comparator.comparingInt(SegmentCompResponse::getSeq))
				.toList();
			assertThat(segments).as("script timeframes must reach asset_segment_comp").hasSize(2);
			assertThat(segments.get(0).getTimeFrom()).isEqualTo(2000L);
			assertThat(segments.get(0).getTimeTo()).isEqualTo(3500L);
			assertThat(segments.get(1).getTimeTo()).isEqualTo(10250L);

			boolean recorded = client.listAssetNodeResults(asset.getUuid()).sync().body().getData().stream()
				.map(NodeResultResponse::getNodeKind)
				.anyMatch(ScriptNode.KIND::equals);
			assertThat(recorded).as("script node-result ledger row must be readable via REST").isTrue();
		});
	}

	@Test
	public void testTwoScriptNodesCoexistOnOneAsset() throws Exception {
		// The whole point of variant = nodeId: a graph may contain several script nodes, and the
		// second must not overwrite the first's component row.
		withLoom(client -> {
			AssetResponse asset = getOrCreateAsset(client, video1(), "video/mp4");
			Path metaPath = Files.createTempDirectory("node-it-script-multi");
			CortexOptions options = new CortexOptions().setMetaPath(metaPath);

			JsonObject outputs = new JsonObject().put("outputs",
				new JsonArray().add(new JsonObject().put("key", "note").put("type", "TEXT")));

			ScriptNode first = node(client, options, new JsonObject().put("id", "script-a").put("type", ScriptNode.KIND)
				.put("script", "out.text('note', 'from a');").mergeIn(outputs));
			ScriptNode second = node(client, options, new JsonObject().put("id", "script-b").put("type", ScriptNode.KIND)
				.put("script", "out.text('note', 'from b');").mergeIn(outputs));

			assertThat(first.process(NodeContext.create(media(video1()), NodeInputs.empty())).getState()).isEqualTo(ResultState.SUCCESS);
			assertThat(second.process(NodeContext.create(media(video1()), NodeInputs.empty())).getState()).isEqualTo(ResultState.SUCCESS);

			Map<String, String> byVariant = client.listAssetJsonComps(asset.getUuid()).sync().body().getData().stream()
				.filter(c -> ScriptNode.SCHEMA_TYPE.equals(c.getSchemaType()))
				.filter(c -> c.getVariant() != null && c.getVariant().startsWith("script-"))
				.collect(java.util.stream.Collectors.toMap(JsonCompResponse::getVariant, c -> c.getData().getString("note")));

			assertThat(byVariant).containsEntry("script-a", "from a").containsEntry("script-b", "from b");
		});
	}
}
