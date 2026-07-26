package io.metaloom.loom.rest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.graph.PipelineGraph;
import io.metaloom.loom.pipeline.graph.PipelineGraphParser;
import io.metaloom.loom.rest.model.processor.ProcessorCapability;
import io.metaloom.loom.rest.model.processor.ProcessorState;
import io.metaloom.loom.rest.model.processor.message.ProcessorRegistration;
import io.metaloom.loom.rest.service.impl.PipelineEndpointService;
import io.metaloom.loom.rest.service.impl.ProcessorRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The capability precheck that makes a pipeline run fail fast with 503 when the
 * pool cannot cover every node kind in the graph — not just the source kind.
 *
 * <p>Exercises {@link PipelineEndpointService#unsupportedNodeKinds} directly against a
 * real {@link ProcessorRegistry}, which is the exact decision {@code dispatchRun} makes
 * before it turns an unschedulable run into a 503.</p>
 */
public class PipelineRunCapabilityTest {

	/** A single online CPU worker restricted to the given kinds (null/empty = unrestricted). */
	private ProcessorRegistry registryWith(Set<String> kinds) {
		ProcessorRegistry registry = new ProcessorRegistry();
		ProcessorRegistration registration = new ProcessorRegistration()
			.setNodeId("worker")
			.setName("worker")
			.setPriority(1)
			.setCapabilities(Set.of(ProcessorCapability.CPU))
			.setNodeWhitelist(kinds);
		// No socket: selection is a pure decision over registered metadata.
		registry.register("worker", registration, null);
		registry.get("worker").state = ProcessorState.ONLINE;
		return registry;
	}

	private static JsonObject node(String id, String type, boolean source) {
		JsonObject node = new JsonObject().put("id", id).put("type", type).put("name", type);
		if (source) {
			node.put("source", true).put("options", new JsonObject().put("path", "/media"));
		}
		return node;
	}

	private static JsonObject edge(String id, String from, String to) {
		return new JsonObject().put("id", id).put("source", from).put("target", to);
	}

	/** filesystem-source -> (one downstream node of the given kind). */
	private PipelineGraph graphWithDownstream(String downstreamKind) {
		JsonObject def = new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("pn1", "filesystem-source", true))
				.add(node("pn2", downstreamKind, false)))
			.put("edges", new JsonArray().add(edge("pe1", "pn1", "pn2")));
		return new PipelineGraphParser().parse("t", def, true, false, 1);
	}

	@Test
	void testRejectsWhenADownstreamKindHasNoWorker() {
		PipelineGraph graph = graphWithDownstream("whisper");
		// The worker runs the source but not whisper: the run must be flagged
		// unschedulable even though its source kind is covered.
		ProcessorRegistry registry = registryWith(Set.of("filesystem-source", "sha256"));

		assertEquals(Set.of("whisper"), PipelineEndpointService.unsupportedNodeKinds(graph, registry));
	}

	@Test
	void testAcceptsWhenEveryKindHasAWorker() {
		PipelineGraph graph = graphWithDownstream("whisper");
		ProcessorRegistry registry = registryWith(Set.of("filesystem-source", "whisper"));

		assertTrue(PipelineEndpointService.unsupportedNodeKinds(graph, registry).isEmpty(),
			"A fully-covered graph must be schedulable");
	}

	@Test
	void testUnrestrictedWorkerCoversEveryKind() {
		PipelineGraph graph = graphWithDownstream("whisper");
		// A worker that predates whitelisting (null whitelist) accepts anything.
		ProcessorRegistry registry = registryWith(null);

		assertTrue(PipelineEndpointService.unsupportedNodeKinds(graph, registry).isEmpty());
	}

	@Test
	void testReportsEveryMissingKindNotJustTheFirst() {
		JsonObject def = new JsonObject()
			.put("nodes", new JsonArray()
				.add(node("pn1", "filesystem-source", true))
				.add(node("pn2", "whisper", false))
				.add(node("pn3", "ocr", false)))
			.put("edges", new JsonArray()
				.add(edge("pe1", "pn1", "pn2"))
				.add(edge("pe2", "pn1", "pn3")));
		PipelineGraph graph = new PipelineGraphParser().parse("t", def, true, false, 1);
		ProcessorRegistry registry = registryWith(Set.of("filesystem-source"));

		assertEquals(Set.of("whisper", "ocr"), PipelineEndpointService.unsupportedNodeKinds(graph, registry));
	}

	@Test
	void testSourceKindItselfCanBeUnsupported() {
		PipelineGraph graph = graphWithDownstream("sha256");
		// A pool with no worker for the source kind cannot start the scan at all.
		ProcessorRegistry registry = registryWith(Set.of("sha256"));

		assertEquals(Set.of("filesystem-source"), PipelineEndpointService.unsupportedNodeKinds(graph, registry));
	}
}
