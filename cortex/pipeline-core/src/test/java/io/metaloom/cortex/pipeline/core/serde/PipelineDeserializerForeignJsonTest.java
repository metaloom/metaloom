package io.metaloom.cortex.pipeline.core.serde;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.metaloom.cortex.pipeline.api.NodeMode;
import io.metaloom.cortex.pipeline.api.Pipeline;
import io.metaloom.cortex.pipeline.api.filter.FilterBranch;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;

/**
 * Parses hand-written JSON rather than {@code PipelineSerializer} output.
 *
 * <p>{@code PipelineSerdeRoundTripTest} only proves the deserializer can read
 * what the serializer just wrote — the two agree even if both are wrong, and
 * defaults are never exercised because the serializer always emits every field.
 * These fixtures are written the way an external tool or a hand-authored
 * definition would write them: sparse, differently ordered, with unknown fields
 * and out-of-vocabulary enum values.</p>
 */
class PipelineDeserializerForeignJsonTest {

	private PipelineDeserializer deserializer;

	@BeforeEach
	void setUp() {
		deserializer = new PipelineDeserializer(new ObjectMapper());
	}

	private static PipelineNode node(Pipeline pipeline, String id) {
		return pipeline.nodes().stream()
				.filter(n -> id.equals(n.id()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No node '" + id + "' in " + ids(pipeline)));
	}

	private static List<String> ids(Pipeline pipeline) {
		return pipeline.nodes().stream().map(PipelineNode::id).toList();
	}

	@Test
	void testMinimalDefinitionRelyingEntirelyOnDefaults() throws JsonProcessingException {
		String json = """
				{
				  "name": "minimal",
				  "nodes": [
				    { "id": "src", "type": "source" }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(pipeline.name()).isEqualTo("minimal");
		assertThat(pipeline.description()).isEmpty();
		assertThat(pipeline.priority()).isZero();
		assertThat(pipeline.isEnabled()).as("enabled defaults to true").isTrue();
		assertThat(pipeline.isDryRun()).isFalse();

		PipelineNode src = node(pipeline, "src");
		assertThat(src.isSource()).isTrue();
		assertThat(src.name()).as("name falls back to the id").isEqualTo("src");
		assertThat(src.mode()).isEqualTo(NodeMode.PARALLEL);
		assertThat(src.isBlocking()).isTrue();
		assertThat(src.concurrency()).isEqualTo(1);
		assertThat(src.syncToLoom()).isFalse();
	}

	@Test
	void testGraphIsReconstructedFromDependencies() throws JsonProcessingException {
		String json = """
				{
				  "name": "chain",
				  "nodes": [
				    { "id": "llm",    "type": "llm",    "dependencies": ["tika"] },
				    { "id": "tika",   "type": "tika",   "dependencies": ["src"] },
				    { "id": "src",    "type": "source" }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(ids(pipeline))
				.as("all three nodes are discovered, in topological order from the source")
				.containsExactly("src", "tika", "llm");
		assertThat(node(pipeline, "tika").dependencies()).containsExactly("src");
		assertThat(node(pipeline, "llm").dependencies()).containsExactly("tika");
	}

	@Test
	void testSourceNodeCanBeNamedByTheTopLevelSourceNodeField() throws JsonProcessingException {
		String json = """
				{
				  "name": "explicit-source",
				  "sourceNode": "entry",
				  "nodes": [
				    { "id": "entry", "type": "filesystem" },
				    { "id": "hash",  "type": "sha512", "dependencies": ["entry"] }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(node(pipeline, "entry").isSource())
				.as("type is 'filesystem', so only the sourceNode field marks it")
				.isTrue();
		assertThat(ids(pipeline)).containsExactly("entry", "hash");
	}

	@Test
	void testConditionalDependenciesBecomeFilterBranches() throws JsonProcessingException {
		String json = """
				{
				  "name": "branched",
				  "nodes": [
				    { "id": "src",    "type": "source" },
				    { "id": "filter", "type": "filter-mimetype", "dependencies": ["src"] },
				    { "id": "hash",   "type": "sha512", "dependencies": ["filter"],
				      "conditionalDependencies": { "filter": "PASS" } },
				    { "id": "report", "type": "loom", "dependencies": ["filter"],
				      "conditionalDependencies": { "filter": "REJECT" } }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(node(pipeline, "hash").conditionalDependencies())
				.containsEntry("filter", FilterBranch.PASS);
		assertThat(node(pipeline, "report").conditionalDependencies())
				.containsEntry("filter", FilterBranch.REJECT);
	}

	@Test
	void testNonDefaultNodeSettingsAreRead() throws JsonProcessingException {
		String json = """
				{
				  "name": "configured",
				  "nodes": [
				    { "id": "src", "type": "source" },
				    { "id": "whisper", "name": "Whisper ASR", "type": "whisper",
				      "dependencies": ["src"], "mode": "SEQUENTIAL", "blocking": false,
				      "concurrency": 8, "syncToLoom": true, "timeoutMs": 30000 }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);
		PipelineNode whisper = node(pipeline, "whisper");

		assertThat(whisper.name()).isEqualTo("Whisper ASR");
		assertThat(whisper.mode()).isEqualTo(NodeMode.SEQUENTIAL);
		assertThat(whisper.isBlocking()).isFalse();
		assertThat(whisper.concurrency()).isEqualTo(8);
		assertThat(whisper.syncToLoom()).isTrue();
	}

	/**
	 * Node options arrive as untyped JSON. The conversion must preserve the
	 * distinction between numbers, booleans and strings — a filter threshold that
	 * arrives as the String {@code "0.8"} would silently disable the filter.
	 */
	@Test
	void testOptionValuesKeepTheirJsonTypes() throws JsonProcessingException {
		String json = """
				{
				  "name": "typed-options",
				  "nodes": [
				    { "id": "src", "type": "source", "options": {
				        "threshold": 0.8,
				        "maxItems": 100,
				        "enabled": true,
				        "label": "primary",
				        "extensions": ["mp4", "mkv"],
				        "nested": { "depth": 2 }
				    } }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(node(pipeline, "src").options())
				.containsEntry("threshold", 0.8d)
				.containsEntry("maxItems", 100L)
				.containsEntry("enabled", true)
				.containsEntry("label", "primary")
				.containsEntry("extensions", List.of("mp4", "mkv"))
				.containsEntry("nested", java.util.Map.of("depth", 2L));
	}

	@Test
	void testUnknownFieldsAreIgnored() throws JsonProcessingException {
		String json = """
				{
				  "name": "with-extras",
				  "schemaVersion": 3,
				  "author": "some-ui",
				  "nodes": [
				    { "id": "src", "type": "source", "x": 120, "y": 45, "color": "#ff0000" }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(pipeline.name()).isEqualTo("with-extras");
		assertThat(ids(pipeline)).containsExactly("src");
	}

	@Test
	void testOutOfVocabularyEnumValuesFallBackToTheDefault() throws JsonProcessingException {
		String json = """
				{
				  "name": "bad-enums",
				  "nodes": [
				    { "id": "src", "type": "source", "mode": "TURBO" },
				    { "id": "hash", "type": "sha512", "dependencies": ["src"],
				      "conditionalDependencies": { "src": "MAYBE" } }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(node(pipeline, "src").mode())
				.as("an unknown mode falls back to PARALLEL rather than failing the parse")
				.isEqualTo(NodeMode.PARALLEL);
		assertThat(node(pipeline, "hash").conditionalDependencies())
				.as("an unknown branch falls back to ANY, which is wired as a plain unconditional edge")
				.isEmpty();
		assertThat(node(pipeline, "hash").dependencies())
				.as("the edge itself still exists")
				.containsExactly("src");
	}

	@Test
	void testDependencyOnAnUnknownNodeIdIsIgnored() throws JsonProcessingException {
		String json = """
				{
				  "name": "dangling",
				  "nodes": [
				    { "id": "src", "type": "source" },
				    { "id": "hash", "type": "sha512", "dependencies": ["src", "does-not-exist"] }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(node(pipeline, "hash").dependencies())
				.as("the dangling edge is dropped, the real one survives")
				.containsExactly("src");
	}

	@Test
	void testNodeWithoutAnIdIsSkipped() throws JsonProcessingException {
		String json = """
				{
				  "name": "missing-id",
				  "nodes": [
				    { "id": "src", "type": "source" },
				    { "type": "sha512" }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(ids(pipeline)).containsExactly("src");
	}

	@Test
	void testDefinitionWithoutASourceNodeIsRejected() {
		String json = """
				{
				  "name": "no-source",
				  "nodes": [
				    { "id": "hash", "type": "sha512" }
				  ]
				}
				""";

		assertThatThrownBy(() -> deserializer.deserialize(json))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("No source node found in deserialized pipeline 'no-source'");
	}

	@Test
	void testMalformedJsonIsRejected() {
		assertThatThrownBy(() -> deserializer.deserialize("{ this is not json }"))
				.isInstanceOf(JsonProcessingException.class);
	}

	/**
	 * The Loom UI persists a graph as {@code nodes[]} plus a top-level
	 * {@code edges[]} array. The Cortex deserializer only understands
	 * {@code nodes[].dependencies[]}, so an edge-based definition parses into a
	 * set of disconnected nodes and the pipeline silently loses its graph.
	 * Pinned here as the current behaviour; unifying the two formats is Task 1.
	 */
	@Test
	void testEdgesArrayIsNotUnderstood() throws JsonProcessingException {
		String json = """
				{
				  "name": "loom-ui-format",
				  "nodes": [
				    { "id": "src",  "type": "source" },
				    { "id": "hash", "type": "sha512" },
				    { "id": "tika", "type": "tika" }
				  ],
				  "edges": [
				    { "id": "e1", "source": "src",  "target": "hash" },
				    { "id": "e2", "source": "hash", "target": "tika" }
				  ]
				}
				""";

		Pipeline pipeline = deserializer.deserialize(json);

		assertThat(ids(pipeline))
				.as("only the source is reachable — edges[] is ignored (Task 1)")
				.containsExactly("src");
	}
}
