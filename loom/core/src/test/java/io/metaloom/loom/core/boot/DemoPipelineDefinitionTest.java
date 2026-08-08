package io.metaloom.loom.core.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.NodeDescriptorProvider;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.pipeline.graph.ExecutionMode;
import io.metaloom.loom.pipeline.graph.GraphValidationException;
import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphNode;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.vertx.core.json.JsonObject;

/**
 * Every demo pipeline must be a pipeline that can actually run.
 *
 * <p>
 * This parses each seeded definition through the <strong>real</strong> parser against the
 * <strong>real</strong> descriptor registry — the same path a save and a run take. Without it the
 * demo data is only checked by being looked at, which is exactly how the "complex" pipeline came to
 * reference three node kinds that had never existed ({@code resize}, {@code face-detect},
 * {@code s3-output}): nothing validated kinds, so a graph that could never dispatch looked
 * perfectly plausible in the editor.
 * </p>
 *
 * <p>
 * No database is needed; the definitions are plain JSON.
 * </p>
 */
public class DemoPipelineDefinitionTest {

	private static Map<String, JsonObject> definitions() {
		Map<String, JsonObject> definitions = new LinkedHashMap<>();
		definitions.put("simple", DemoDatabaseInitializer.simpleDefinition());
		definitions.put("medium", DemoDatabaseInitializer.mediumDefinition());
		definitions.put("complex", DemoDatabaseInitializer.complexDefinition());
		definitions.put("s3-ingest", DemoDatabaseInitializer.s3IngestDefinition());
		definitions.put("s3-publish", DemoDatabaseInitializer.s3PublishDefinition());
		definitions.put("script", DemoDatabaseInitializer.scriptDefinition());
		definitions.put("transcription", DemoDatabaseInitializer.transcriptionDefinition());
		definitions.put("review-triage", DemoDatabaseInitializer.reviewTriageDefinition());
		return definitions;
	}

	private static PipelineGraphParser realParser() {
		NodeDescriptorRegistry registry = new NodeDescriptorRegistry();
		ServiceLoader.load(NodeDescriptorProvider.class)
			.forEach(provider -> provider.getDescriptors().forEach(registry::register));
		return new PipelineGraphParser(registry);
	}

	@Test
	public void testEveryDemoPipelineParses() {
		PipelineGraphParser parser = realParser();
		for (Map.Entry<String, JsonObject> entry : definitions().entrySet()) {
			try {
				PipelineGraph graph = parser.parse(entry.getKey(), entry.getValue(), true, false, 0);
				assertNotNull(graph.getSourceNode(), "Demo pipeline '" + entry.getKey() + "' has no source node");
			} catch (GraphValidationException e) {
				fail("Demo pipeline '" + entry.getKey() + "' is not executable as drawn: " + e.getMessage());
			}
		}
	}

	@Test
	public void testEveryEdgeNamesItsPorts() {
		for (Map.Entry<String, JsonObject> entry : definitions().entrySet()) {
			var edges = entry.getValue().getJsonArray("edges");
			assertNotNull(edges, "Demo pipeline '" + entry.getKey() + "' has no edges");
			for (int i = 0; i < edges.size(); i++) {
				JsonObject edge = edges.getJsonObject(i);
				assertNotNull(edge.getString("sourcePort"),
					"Demo pipeline '" + entry.getKey() + "' edge " + edge.getString("id") + " has no sourcePort");
				assertNotNull(edge.getString("targetPort"),
					"Demo pipeline '" + entry.getKey() + "' edge " + edge.getString("id") + " has no targetPort");
			}
		}
	}

	/**
	 * The complex demo is the one that shows the sequence model, so assert that it still does.
	 *
	 * <p>
	 * {@code facedetect} emits a sequence — one element per detected face — and
	 * {@code facedescription} declares a sequence input, so it <em>gathers</em> the faces of an
	 * image and runs once with all of them. It is deliberately not a per-element node: no shipped
	 * kind declares a single-detection input, so a demo claiming one run per face would be
	 * describing something the graph does not do.
	 * </p>
	 */
	@Test
	public void testTheComplexDemoStillDemonstratesASequence() {
		PipelineGraph graph = realParser().parse("complex", DemoDatabaseInitializer.complexDefinition(), true, false, 0);

		PipelineGraphNode detect = graph.getNode("pn5");
		PipelineGraphNode describe = graph.getNode("pn6");
		assertNotNull(detect, "The complex demo should still contain the facedetect node");
		assertNotNull(describe, "The complex demo should still contain the facedescription node");
		assertEquals("facedetect", detect.getKind());
		assertEquals("facedescription", describe.getKind());

		// The gather runs once for the whole sequence; that is what a MANY input means.
		assertEquals(ExecutionMode.SINGLE, describe.getExecutionMode(),
			"facedescription gathers the detections of an image, so it runs once per item");
		assertTrue(describe.getInputBindings().stream()
			.anyMatch(binding -> binding.sourceNodeId().equals("pn5")
				&& binding.sourcePortId().equals("detections")
				&& binding.targetIsMany()),
			"facedescription should gather facedetect's detections through a sequence input");
	}
}
