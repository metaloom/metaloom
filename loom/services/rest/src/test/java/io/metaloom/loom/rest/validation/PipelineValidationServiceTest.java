package io.metaloom.loom.rest.validation;

import static io.metaloom.loom.rest.validation.PipelineValidationService.BRANCH_NOT_FILTER;
import static io.metaloom.loom.rest.validation.PipelineValidationService.BRANCH_UNKNOWN;
import static io.metaloom.loom.rest.validation.PipelineValidationService.CYCLE;
import static io.metaloom.loom.rest.validation.PipelineValidationService.DEFINITION_MISSING;
import static io.metaloom.loom.rest.validation.PipelineValidationService.EDGES_NOT_ARRAY;
import static io.metaloom.loom.rest.validation.PipelineValidationService.EDGE_NULL;
import static io.metaloom.loom.rest.validation.PipelineValidationService.EDGE_SOURCE_MISSING;
import static io.metaloom.loom.rest.validation.PipelineValidationService.EDGE_SOURCE_UNKNOWN;
import static io.metaloom.loom.rest.validation.PipelineValidationService.EDGE_TARGET_MISSING;
import static io.metaloom.loom.rest.validation.PipelineValidationService.EDGE_TARGET_UNKNOWN;
import static io.metaloom.loom.rest.validation.PipelineValidationService.EMPTY_GRAPH;
import static io.metaloom.loom.rest.validation.PipelineValidationService.NODES_NOT_ARRAY;
import static io.metaloom.loom.rest.validation.PipelineValidationService.NODE_ID_DUPLICATE;
import static io.metaloom.loom.rest.validation.PipelineValidationService.NODE_ID_INVALID;
import static io.metaloom.loom.rest.validation.PipelineValidationService.NODE_ID_MISSING;
import static io.metaloom.loom.rest.validation.PipelineValidationService.NODE_NULL;
import static io.metaloom.loom.rest.validation.PipelineValidationService.NODE_TYPE_MISSING;
import static io.metaloom.loom.rest.validation.PipelineValidationService.NODE_TYPE_UNKNOWN;
import static io.metaloom.loom.rest.validation.PipelineValidationService.UNREACHABLE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.nodes.spec.ContentTypeRegistry;
import io.metaloom.loom.nodes.spec.NodeCategory;
import io.metaloom.loom.nodes.spec.NodeDescriptor;
import io.metaloom.loom.nodes.spec.NodeDescriptorRegistry;
import io.metaloom.loom.nodes.spec.NodeMode;
import io.metaloom.loom.nodes.spec.PortSpec;
import io.metaloom.loom.rest.model.pipeline.PipelineValidationError;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Tests for {@link PipelineValidationService}, the single authority on what a pipeline definition
 * may look like.
 *
 * <p>
 * Every case goes through {@link #rejected(JsonObject)} / {@link #accepted(JsonObject)}, which
 * exercise <b>both</b> entry points and assert they agree: the collecting
 * {@code collectErrors} that backs {@code POST /pipelines/validate}, and the throwing
 * {@code validateDefinition} that backs create and update. Two doors onto one rule set is only safe
 * while they answer the same question the same way, so that equivalence is asserted per case rather
 * than once.
 * </p>
 */
public class PipelineValidationServiceTest {

	private NodeDescriptorRegistry registry;
	private PipelineValidationService service;

	@BeforeEach
	public void setUp() {
		registry = new NodeDescriptorRegistry();

		// Register some test node descriptors
		registry.register(createDescriptor("sha512", "SHA-512 Hash", NodeCategory.ANALYSIS));
		registry.register(createDescriptor("sha256", "SHA-256 Hash", NodeCategory.ANALYSIS));
		registry.register(createDescriptor("md5", "MD5 Hash", NodeCategory.ANALYSIS));
		registry.register(createDescriptor("thumbnail", "Thumbnail", NodeCategory.ANALYSIS));
		registry.register(createDescriptor("facedetect", "Face Detect", NodeCategory.ANALYSIS));
		registry.register(createDescriptor("filter", "Mime Type Filter", NodeCategory.FILTER));
		registry.register(createDescriptor("filesystem-source", "Filesystem Source", NodeCategory.SOURCE));

		service = new PipelineValidationService(registry);
	}

	private NodeDescriptor createDescriptor(String kind, String name, NodeCategory category) {
		return new NodeDescriptor()
			.setNodeId(kind)
			.setName(name)
			.setDescription("Test node: " + name)
			.setIcon("test")
			.setCategory(category)
			// A source has nothing to receive; everything else takes media and passes media on, so
			// any two of these fixtures can be wired together. The input is declared *optional*
			// deliberately: these cases are about ids, cycles, reachability and filter branches, and
			// a required input would make every one of them fail on port satisfaction instead -
			// which is a rule with its own coverage.
			.setInputPorts(category == NodeCategory.SOURCE
				? List.of()
				: List.of(PortSpec.optionalOne("media", ContentTypeRegistry.MEDIA_ANY),
					// A sequence port so a fixture can model a converging (diamond) graph: a ONE
					// port with two incoming edges is an error now, and the fan-in cases below are
					// about cycles and reachability rather than about cardinality.
					PortSpec.optionalMany("media_seq", ContentTypeRegistry.MEDIA_ANY)))
			.setOutputPorts(List.of(PortSpec.one("media", ContentTypeRegistry.MEDIA_ANY)))
			.setParameters(List.of())
			.setDefaultConcurrency(1)
			.setDefaultMode(NodeMode.PARALLEL)
			.setDefaultBlocking(true)
			.setEvents(List.of("NODE_STARTED", "NODE_COMPLETED", "NODE_FAILED", "NODE_SKIPPED"));
	}

	// ── Assertions ──────────────────────────────────────────────────────────

	/**
	 * Assert the definition is refused, and that the throwing wrapper reports the first collected
	 * error verbatim.
	 *
	 * @return the collected errors, for the caller to assert codes and positions on
	 */
	private List<PipelineValidationError> rejected(JsonObject definition) {
		List<PipelineValidationError> errors = service.collectErrors(definition);
		assertFalse(errors.isEmpty(), "Expected the definition to be rejected, but no error was collected");
		ValidationException ex = assertThrows(ValidationException.class,
			() -> service.validateDefinition(definition));
		assertEquals(errors.get(0).getMessage(), ex.getMessage(),
			"The throwing wrapper must report the first collected error");
		return errors;
	}

	/** Assert the definition is accepted by both entry points. */
	private void accepted(JsonObject definition) {
		assertEquals(List.of(), codes(service.collectErrors(definition)),
			"Expected the definition to be accepted");
		assertDoesNotThrow(() -> service.validateDefinition(definition));
	}

	private static List<String> codes(List<PipelineValidationError> errors) {
		return errors.stream().map(PipelineValidationError::getCode).toList();
	}

	// ── Valid pipeline tests ────────────────────────────────────────────────

	@Test
	public void testValidSimplePipeline() {
		accepted(createPipelineDefinition(
			List.of(
				createNode("source", "sha512"),
				createNode("hash", "sha256")),
			List.of(createEdge("source", "hash"))));
	}

	@Test
	public void testValidPipelineWithMultipleNodes() {
		accepted(createPipelineDefinition(
			List.of(
				createNode("source", "sha512"),
				createNode("hash1", "sha256"),
				createNode("hash2", "md5"),
				createNode("thumb", "thumbnail")),
			List.of(
				createEdge("source", "hash1"),
				createEdge("source", "hash2"),
				// Both branches converge on 'thumb', so they must land on its sequence port.
				createEdge("hash1", "thumb", "media_seq"),
				createEdge("hash2", "thumb", "media_seq"))));
	}

	@Test
	public void testValidPipelineWithFilterNode() {
		accepted(createPipelineDefinition(
			List.of(
				createNode("source", "sha512"),
				createNode("filter", "facedetect"),
				createNode("hash", "sha256")),
			List.of(
				createEdge("source", "filter"),
				createEdge("filter", "hash"))));
	}

	// ── Reporting every problem at once ─────────────────────────────────────

	/**
	 * The point of the collecting API: an author with four independent mistakes on the canvas is
	 * told about four, not told about one four times.
	 */
	@Test
	@DisplayName("Independent node problems are all reported in one pass")
	public void testEveryNodeProblemIsReported() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(
				createNode("Source", "sha512"),
				createNode("hash", "no-such-kind"),
				createNode("dup", "md5"),
				createNode("dup", "sha256"),
				new JsonObject().put("id", "typeless")),
			List.of()));

		assertEquals(List.of(NODE_ID_INVALID, NODE_TYPE_UNKNOWN, NODE_ID_DUPLICATE, NODE_TYPE_MISSING), codes(errors));
		// Each is pinned to the node it belongs to, so the editor can mark them on the canvas.
		assertEquals(List.of("Source", "hash", "dup", "typeless"),
			errors.stream().map(PipelineValidationError::getNodeId).toList());
	}

	@Test
	@DisplayName("Independent edge problems are all reported in one pass")
	public void testEveryEdgeProblemIsReported() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(
				createNode("source", "sha512"),
				createNode("hash", "sha256")),
			List.of(
				createEdge("ghost", "hash"),
				createEdge("source", "phantom"),
				new JsonObject().put("target", "hash"))));

		assertEquals(List.of(EDGE_SOURCE_UNKNOWN, EDGE_TARGET_UNKNOWN, EDGE_SOURCE_MISSING), codes(errors));
		// Definitions carry no edge ids, so an edge is named by the pair it connects.
		assertEquals("ghost->hash", errors.get(0).getEdgeId());
	}

	/**
	 * Broken node ids stop the pass before the edge checks run.
	 *
	 * <p>
	 * Not an oversight: every later rule addresses nodes by id, so a graph with a duplicated id
	 * would report every edge touching it as dangling — derived noise burying the one thing the
	 * author has to fix first.
	 * </p>
	 */
	@Test
	public void testEdgeChecksAreSkippedWhileTheNodesAreStillBroken() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(createNode("Source", "sha512")),
			List.of(createEdge("ghost", "phantom"))));

		assertEquals(List.of(NODE_ID_INVALID), codes(errors));
	}

	/** Likewise the port checks, which would only restate a structural problem in other words. */
	@Test
	public void testPortChecksAreSkippedWhileTheGraphIsStillBroken() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(
				createNode("a", "sha512"),
				createNode("b", "sha256")),
			List.of(
				new JsonObject().put("source", "a").put("target", "b"),
				createEdge("b", "a"))));

		// The unnamed-port complaint would come from the parser; the cycle is what has to be fixed.
		assertEquals(List.of(CYCLE), codes(errors));
	}

	// ── Invalid node ID tests ───────────────────────────────────────────────

	@Test
	public void testInvalidNodeIdUppercase() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(createNode("Source", "sha512")), List.of()));
		assertEquals(List.of(NODE_ID_INVALID), codes(errors));
		assertTrue(errors.get(0).getMessage().contains("Source"), errors.get(0).getMessage());
	}

	@Test
	public void testInvalidNodeIdSpecialChars() {
		assertEquals(List.of(NODE_ID_INVALID), codes(rejected(createPipelineDefinition(
			List.of(createNode("source-node!", "sha512")), List.of()))));
	}

	@Test
	public void testInvalidNodeIdTooLong() {
		String longId = "a".repeat(65); // Max is 64
		assertEquals(List.of(NODE_ID_INVALID), codes(rejected(createPipelineDefinition(
			List.of(createNode(longId, "sha512")), List.of()))));
	}

	/**
	 * The id ends up in URLs, log lines and edge references, so it is restricted to lowercase
	 * alphanumerics and inner hyphens.
	 */
	@Test
	public void testTheNodeIdPatternIsEnforced() {
		for (String bad : new String[] { "PN1", "pn 1", "-pn1", "pn1-", "pn_1", "pn.1", "ä" }) {
			assertEquals(List.of(NODE_ID_INVALID),
				codes(rejected(createPipelineDefinition(List.of(createNode(bad, "sha512")), List.of()))),
				"Expected '" + bad + "' to be refused");
		}
		for (String good : new String[] { "a", "1", "pn1", "filesystem-source-1", "a-b-c", "a".repeat(64) }) {
			accepted(createPipelineDefinition(List.of(createNode(good, "sha512")), List.of()));
		}
	}

	@Test
	public void testMissingNodeId() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(new JsonObject().put("type", "sha512")), List.of()));
		assertEquals(List.of(NODE_ID_MISSING), codes(errors));
		assertTrue(errors.get(0).getMessage().contains("index 0"), errors.get(0).getMessage());
	}

	// ── Duplicate node ID tests ─────────────────────────────────────────────

	@Test
	public void testDuplicateNodeIds() {
		// Edges address nodes by id, so two nodes sharing one makes every edge to it ambiguous.
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(
				createNode("source", "sha512"),
				createNode("source", "sha256")),
			List.of()));
		assertEquals(List.of(NODE_ID_DUPLICATE), codes(errors));
		assertEquals("source", errors.get(0).getNodeId());
	}

	// ── Missing / unknown node type tests ───────────────────────────────────

	@Test
	public void testMissingNodeType() {
		assertEquals(List.of(NODE_TYPE_MISSING), codes(rejected(createPipelineDefinition(
			List.of(new JsonObject().put("id", "source")), List.of()))));
		assertEquals(List.of(NODE_TYPE_MISSING), codes(rejected(createPipelineDefinition(
			List.of(createNode("source", "  ")), List.of()))), "A blank type is no type");
	}

	@Test
	public void testUnknownNodeType() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(createNode("source", "unknown-type")), List.of()));
		assertEquals(List.of(NODE_TYPE_UNKNOWN), codes(errors));
		assertTrue(errors.get(0).getMessage().contains("unknown-type"), errors.get(0).getMessage());
	}

	// ── Shape of the definition itself ──────────────────────────────────────

	/**
	 * The wrong <em>type</em> under a known key is a client error, not an internal one. Reading it
	 * straight through {@code getJsonArray} threw {@code ClassCastException} out of the validator,
	 * which the REST layer has no case for and answers with a 500.
	 */
	@Test
	public void testANonArrayNodesFieldIsABadRequestRatherThanACrash() {
		assertEquals(List.of(NODES_NOT_ARRAY), codes(rejected(
			new JsonObject().put("nodes", new JsonObject().put("pn1", "sha512")))));
	}

	@Test
	public void testANonArrayEdgesFieldIsABadRequestRatherThanACrash() {
		assertEquals(List.of(EDGES_NOT_ARRAY), codes(rejected(createPipelineDefinition(
			List.of(createNode("source", "sha512")), List.of()).put("edges", "source->hash"))));
	}

	@Test
	public void testANullEntryInAnArrayIsRejectedByPosition() {
		List<PipelineValidationError> nodeErrors = rejected(new JsonObject()
			.put("nodes", new JsonArray().add(createNode("source", "sha512")).addNull()));
		assertEquals(List.of(NODE_NULL), codes(nodeErrors));
		assertTrue(nodeErrors.get(0).getMessage().contains("index 1"), nodeErrors.get(0).getMessage());

		List<PipelineValidationError> edgeErrors = rejected(createPipelineDefinition(
			List.of(createNode("source", "sha512")), List.of())
				.put("edges", new JsonArray().addNull()));
		assertEquals(List.of(EDGE_NULL), codes(edgeErrors));
		assertTrue(edgeErrors.get(0).getMessage().contains("index 0"), edgeErrors.get(0).getMessage());
	}

	// ── Edge validation tests ───────────────────────────────────────────────

	@Test
	public void testEdgeReferencesNonExistentSource() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(createNode("target", "sha256")),
			List.of(createEdge("nonexistent", "target"))));
		assertEquals(List.of(EDGE_SOURCE_UNKNOWN), codes(errors));
		assertTrue(errors.get(0).getMessage().contains("does not match any node ID"), errors.get(0).getMessage());
	}

	@Test
	public void testEdgeReferencesNonExistentTarget() {
		assertEquals(List.of(EDGE_TARGET_UNKNOWN), codes(rejected(createPipelineDefinition(
			List.of(createNode("source", "sha512")),
			List.of(createEdge("source", "nonexistent"))))));
	}

	@Test
	public void testEdgeMissingSource() {
		assertEquals(List.of(EDGE_SOURCE_MISSING), codes(rejected(createPipelineDefinition(
			List.of(createNode("target", "sha256")),
			List.of(new JsonObject().put("target", "target"))))));
	}

	@Test
	public void testEdgeMissingTarget() {
		assertEquals(List.of(EDGE_TARGET_MISSING), codes(rejected(createPipelineDefinition(
			List.of(createNode("source", "sha512")),
			List.of(new JsonObject().put("source", "source"))))));
	}

	// ── Cycle detection tests ───────────────────────────────────────────────

	@Test
	public void testDirectCycle() {
		assertEquals(List.of(CYCLE), codes(rejected(createPipelineDefinition(
			List.of(
				createNode("a", "sha512"),
				createNode("b", "sha256")),
			List.of(
				createEdge("a", "b"),
				createEdge("b", "a"))))));
	}

	@Test
	public void testIndirectCycle() {
		assertEquals(List.of(CYCLE), codes(rejected(createPipelineDefinition(
			List.of(
				createNode("a", "sha512"),
				createNode("b", "sha256"),
				createNode("c", "md5")),
			List.of(
				createEdge("a", "b"),
				createEdge("b", "c"),
				createEdge("c", "a"))))));
	}

	@Test
	public void testSelfLoop() {
		// The one-node loop is the case a naive "have I seen this node twice" check misses.
		assertEquals(List.of(CYCLE), codes(rejected(createPipelineDefinition(
			List.of(createNode("a", "sha512")),
			List.of(createEdge("a", "a"))))));
	}

	@Test
	public void testACycleIsFoundEvenWhenPartOfTheGraphIsAcyclic() {
		// Kahn's drains the acyclic prefix first; the check is that the *remaining* nodes are
		// noticed rather than the traversal simply finishing. Reachability is deliberately silent
		// here — every node off the cycle is unreachable *because* of the cycle.
		assertEquals(List.of(CYCLE), codes(rejected(createPipelineDefinition(
			List.of(
				createNode("src", "filesystem-source"),
				createNode("a", "sha512"),
				createNode("b", "md5"),
				createNode("c", "sha256")),
			List.of(
				createEdge("src", "a"),
				createEdge("b", "c"),
				createEdge("c", "b"))))));
	}

	@Test
	public void testNoCycleInValidDAG() {
		// Kahn's counts visited nodes, so a join reached by two paths must not be mistaken for a loop.
		accepted(createPipelineDefinition(
			List.of(
				createNode("a", "sha512"),
				createNode("b", "sha256"),
				createNode("c", "md5"),
				createNode("d", "thumbnail")),
			List.of(
				createEdge("a", "b"),
				createEdge("a", "c"),
				createEdge("b", "d", "media_seq"),
				createEdge("c", "d", "media_seq"))));
	}

	// ── Reachability tests ──────────────────────────────────────────────────

	@Test
	public void testUnreachableNodeIsRejected() {
		// 'orphan' is a second dependency-free root left behind after 'source' was
		// marked as the source. It is connected to nothing the source produces.
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(
				createSourceNode("source", "filesystem-source"),
				createNode("hash", "sha256"),
				createNode("orphan", "md5")),
			List.of(createEdge("source", "hash"))));

		assertEquals(List.of(UNREACHABLE), codes(errors));
		assertEquals("orphan", errors.get(0).getNodeId());
		assertTrue(errors.get(0).getMessage().contains("orphan"), errors.get(0).getMessage());
	}

	@Test
	@DisplayName("Two orphans are two errors, one per node, so both can be marked")
	public void testEachUnreachableNodeGetsItsOwnError() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(
				createSourceNode("source", "filesystem-source"),
				createNode("hash", "sha256"),
				createNode("orphan-a", "md5"),
				createNode("orphan-b", "thumbnail")),
			List.of(createEdge("source", "hash"), createEdge("orphan-a", "orphan-b"))));

		assertEquals(List.of(UNREACHABLE, UNREACHABLE), codes(errors));
		assertEquals(List.of("orphan-a", "orphan-b"),
			errors.stream().map(PipelineValidationError::getNodeId).toList());
	}

	@Test
	public void testFullyConnectedGraphFromDeclaredSourceIsReachable() {
		accepted(createPipelineDefinition(
			List.of(
				createSourceNode("source", "filesystem-source"),
				createNode("hash", "sha256"),
				createNode("thumb", "thumbnail")),
			List.of(
				createEdge("source", "hash"),
				createEdge("hash", "thumb"))));
	}

	// ── Filter branch tests ─────────────────────────────────────────────────

	@Test
	public void testBranchEdgesFromFilterNodeAreAllowed() {
		accepted(createPipelineDefinition(
			List.of(
				createSourceNode("source", "filesystem-source"),
				createNode("filter", "filter"),
				createNode("keep", "sha256"),
				createNode("drop", "md5")),
			List.of(
				createEdge("source", "filter"),
				createBranchEdge("filter", "keep", "PASS"),
				createBranchEdge("filter", "drop", "REJECT"))));
	}

	@Test
	public void testBranchEdgeFromNonFilterNodeIsRejected() {
		List<PipelineValidationError> errors = rejected(createPipelineDefinition(
			List.of(
				createSourceNode("source", "filesystem-source"),
				createNode("hash", "sha256"),
				createNode("thumb", "thumbnail")),
			List.of(
				createEdge("source", "hash"),
				createBranchEdge("hash", "thumb", "PASS")))); // 'hash' is not a filter

		assertEquals(List.of(BRANCH_NOT_FILTER), codes(errors));
		assertEquals("hash->thumb", errors.get(0).getEdgeId());
	}

	@Test
	public void testUnknownBranchValueIsRejected() {
		assertEquals(List.of(BRANCH_UNKNOWN), codes(rejected(createPipelineDefinition(
			List.of(
				createSourceNode("source", "filesystem-source"),
				createNode("filter", "filter"),
				createNode("keep", "sha256")),
			List.of(
				createEdge("source", "filter"),
				createBranchEdge("filter", "keep", "MAYBE"))))));
	}

	@Test
	public void testAnyBranchFromNonFilterNodeIsAllowed() {
		// An explicit ANY branch is just a plain edge and needs no filter upstream.
		accepted(createPipelineDefinition(
			List.of(
				createSourceNode("source", "filesystem-source"),
				createNode("hash", "sha256")),
			List.of(createBranchEdge("source", "hash", "ANY"))));
	}

	// ── Empty pipeline tests ────────────────────────────────────────────────

	@Test
	public void testEmptyNodesArray() {
		assertEquals(List.of(EMPTY_GRAPH), codes(rejected(new JsonObject().put("nodes", new JsonArray()))));
	}

	@Test
	public void testNullNodesArray() {
		assertEquals(List.of(EMPTY_GRAPH), codes(rejected(new JsonObject())));
	}

	@Test
	public void testNullDefinition() {
		assertEquals(List.of(DEFINITION_MISSING), codes(rejected(null)));
	}

	@Test
	public void testEdgesAreOptional() {
		// A single-node pipeline is legitimate, and so is a definition that simply has no edges yet.
		accepted(new JsonObject().put("nodes", new JsonArray().add(createNode("source", "filesystem-source"))));
	}

	// ── Helper methods ──────────────────────────────────────────────────────

	private JsonObject createPipelineDefinition(List<JsonObject> nodes, List<JsonObject> edges) {
		JsonObject definition = new JsonObject();
		JsonArray nodesArray = new JsonArray();
		nodes.forEach(nodesArray::add);
		definition.put("nodes", nodesArray);

		if (edges != null && !edges.isEmpty()) {
			JsonArray edgesArray = new JsonArray();
			edges.forEach(edgesArray::add);
			definition.put("edges", edgesArray);
		}

		return definition;
	}

	private JsonObject createNode(String id, String type) {
		return new JsonObject()
			.put("id", id)
			.put("type", type);
	}

	private JsonObject createSourceNode(String id, String type) {
		return createNode(id, type).put("source", true);
	}

	/**
	 * Edges name both ports - an edge that names neither is rejected outright now. Every fixture
	 * descriptor speaks {@code media/*} on both sides, so any pair of them is a legal connection.
	 */
	private JsonObject createEdge(String source, String target) {
		return createEdge(source, target, "media");
	}

	/** An edge into a named target port - used where several edges converge on one node. */
	private JsonObject createEdge(String source, String target, String targetPort) {
		return new JsonObject()
			.put("source", source)
			.put("sourcePort", "media")
			.put("target", target)
			.put("targetPort", targetPort);
	}

	private JsonObject createBranchEdge(String source, String target, String branch) {
		return createEdge(source, target).put("branch", branch);
	}
}
