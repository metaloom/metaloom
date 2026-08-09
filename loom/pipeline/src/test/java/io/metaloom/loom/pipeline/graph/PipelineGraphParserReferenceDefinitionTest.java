package io.metaloom.loom.pipeline.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.TestDescriptors;
import io.metaloom.loom.pipeline.model.FilterBranch;
import io.vertx.core.json.JsonObject;

/**
 * The regression fixture the definition format never had.
 *
 * <p>
 * Every other parser test builds its definition in code, so the two things that can drift — what a
 * <em>stored</em> definition looks like, and what the parser makes of it — were only ever checked
 * against each other inside one test method. The de-facto reference was
 * {@code DemoDatabaseInitializer}, which means a format regression was caught only if somebody ran
 * the demo seeder and noticed. This test loads
 * {@code src/test/resources/pipeline/reference-definition.json} — a real file, in the real stored
 * shape — and pins what it parses to.
 * </p>
 *
 * <p>
 * The fixture carries one instance of every feature the format can express: the version tag,
 * {@code options} and the legacy {@code config} alias (including a node with both), selective output
 * ports, affinity, a {@code MANY} output driving a {@code PER_ELEMENT} chain, and the gather that
 * recombines it. That inventory is written out in the fixture's own {@code "//"} block, so anyone
 * changing the format has one file to read and one test to update.
 * </p>
 *
 * <p>
 * <strong>Adding a field to the format means adding it here.</strong> A field only this fixture
 * omits is a field nothing checks the stored representation of.
 * </p>
 */
public class PipelineGraphParserReferenceDefinitionTest {

	private static final String FIXTURE = "/pipeline/reference-definition.json";

	private static JsonObject definition;

	private final PipelineGraphParser parser = new PipelineGraphParser(TestDescriptors.registry());

	@BeforeAll
	static void loadFixture() throws IOException {
		try (InputStream in = PipelineGraphParserReferenceDefinitionTest.class.getResourceAsStream(FIXTURE)) {
			assertNotNull(in, "The reference definition fixture is missing from the classpath: " + FIXTURE);
			definition = new JsonObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private PipelineGraph graph() {
		return parser.parse("reference", definition.copy(), true, false, 100);
	}

	// ── The format tag ────────────────────────────────────────────────────

	@Test
	void testTheFixtureIsWrittenInTheCurrentFormatVersion() {
		// If this fails after a deliberate bump, the fixture is the thing to update: it is what a
		// definition written by *this* Loom looks like.
		assertEquals(PipelineGraphParser.CURRENT_DEFINITION_VERSION,
			PipelineGraphParser.readVersion("reference", definition));
	}

	// ── Shape ─────────────────────────────────────────────────────────────

	@Test
	void testEveryNodeSurvivesParsing() {
		PipelineGraph graph = graph();

		assertEquals(7, graph.size());
		assertEquals(Set.of("src", "route", "notice", "split", "work", "score", "gather"),
			graph.getNodes().stream().map(PipelineGraphNode::getId).collect(Collectors.toSet()));
		assertEquals("src", graph.getSourceNodeId());
	}

	@Test
	void testTopologicalOrderPutsEveryNodeAfterItsDependencies() {
		List<String> order = graph().getTopologicalOrder();

		assertEquals(7, order.size());
		assertEquals("src", order.get(0), "The source has no dependencies and must come first");
		assertEquals("gather", order.get(order.size() - 1), "The gather depends on both branches");

		assertBefore(order, "src", "route");
		assertBefore(order, "route", "split");
		assertBefore(order, "route", "notice");
		assertBefore(order, "split", "work");
		assertBefore(order, "split", "score");
		assertBefore(order, "work", "gather");
		assertBefore(order, "score", "gather");
	}

	private static void assertBefore(List<String> order, String first, String second) {
		assertTrue(order.indexOf(first) < order.indexOf(second),
			first + " must be ordered before " + second + ", got " + order);
	}

	// ── Input bindings ────────────────────────────────────────────────────

	/**
	 * A binding is the whole port tuple, not just an upstream node id. Asserting the tuples is what
	 * makes a silently dropped or mis-targeted edge visible: an edge lost to a dedupe key that is
	 * too coarse still leaves the node with a plausible-looking dependency list.
	 */
	@Test
	void testEveryEdgeBecomesTheInputBindingItDescribes() {
		PipelineGraph graph = graph();

		assertEquals(List.of(), graph.getNode("src").getInputBindings(), "A source consumes nothing");

		assertBinding(graph, "route", 0, "media", "src", "media");
		assertBinding(graph, "notice", 0, "label", "route", "label");
		assertBinding(graph, "split", 0, "media", "route", "a");
		assertBinding(graph, "work", 0, "text", "split", "texts");
		assertBinding(graph, "score", 0, "text", "split", "texts");

		// Two upstreams, two ports - the recombination point.
		assertEquals(2, graph.getNode("gather").getInputBindings().size());
		assertBinding(graph, "gather", 0, "summaries", "work", "result");
		assertBinding(graph, "gather", 1, "sentiments", "score", "score");
	}

	private static void assertBinding(PipelineGraph graph, String nodeId, int index,
		String targetPort, String sourceNode, String sourcePort) {

		List<InputBinding> bindings = graph.getNode(nodeId).getInputBindings();
		assertTrue(bindings.size() > index, nodeId + " has no binding at index " + index + ": " + bindings);
		InputBinding binding = bindings.get(index);
		assertEquals(targetPort, binding.targetPortId(), "target port of " + nodeId + " binding " + index);
		assertEquals(sourceNode, binding.sourceNodeId(), "source node of " + nodeId + " binding " + index);
		assertEquals(sourcePort, binding.sourcePortId(), "source port of " + nodeId + " binding " + index);
		assertEquals(FilterBranch.ANY, binding.branch(), "No edge in the fixture carries a filter branch");
	}

	/**
	 * Cardinality is resolved once, at parse time, and carried on the binding so the engine never
	 * re-consults the descriptor registry at dispatch.
	 */
	@Test
	void testBindingsCarryTheResolvedTargetCardinality() {
		PipelineGraph graph = graph();

		assertFalse(graph.getNode("work").getInputBindings().get(0).targetIsMany(),
			"worker.text is a ONE port - it receives a single element of the sequence");
		assertTrue(graph.getNode("gather").getInputBindings().get(0).targetIsMany(),
			"gatherer.summaries is a MANY port - it receives the whole sequence at once");
		assertTrue(graph.getNode("gather").getInputBindings().get(1).targetIsMany());
	}

	// ── Selective ports ───────────────────────────────────────────────────

	/**
	 * {@code router.a} is selective and {@code router.label} is not, which is the whole point of the
	 * distinction: an item that took the other branch skips everything below {@code a}, but the node
	 * consuming the decision still runs.
	 */
	@Test
	void testSelectivityIsReadFromTheProducingPortAndInherited() {
		PipelineGraph graph = graph();

		InputBinding intoSplit = graph.getNode("split").getInputBindings().get(0);
		assertTrue(intoSplit.sourceSelective(), "route.a is a selective output port");
		assertTrue(intoSplit.routed(), "and an edge leaving a selective port is on a routed path");

		InputBinding intoNotice = graph.getNode("notice").getInputBindings().get(0);
		assertFalse(intoNotice.sourceSelective(), "route.label is not selective");
		assertFalse(intoNotice.routed(), "so the noticer runs whichever branch an item took");

		// Selectivity is transitive: work is two hops below route.a, and if that branch does not
		// fire there is nothing for it to run on. A one-hop rule would leave it running on empty
		// inputs.
		assertTrue(graph.getNode("work").getInputBindings().get(0).routed(),
			"A node downstream of a routed edge is itself routed");
		assertTrue(graph.getNode("gather").getInputBindings().get(0).routed());
	}

	// ── Fan-out and gather ────────────────────────────────────────────────

	/**
	 * The classification the engine dispatches on. {@code splitter.texts} is a {@code MANY} output
	 * wired into {@code ONE} input ports, so those nodes run once per element; the gather's
	 * {@code MANY} inputs put it back to once per item.
	 */
	@Test
	void testExecutionModePerNode() {
		PipelineGraph graph = graph();

		assertEquals(ExecutionMode.SINGLE, graph.getNode("src").getExecutionMode());
		assertEquals(ExecutionMode.SINGLE, graph.getNode("route").getExecutionMode());
		assertEquals(ExecutionMode.SINGLE, graph.getNode("notice").getExecutionMode());
		assertEquals(ExecutionMode.SINGLE, graph.getNode("split").getExecutionMode(),
			"The fan-out driver itself runs once; it is what produces the sequence");

		assertEquals(ExecutionMode.PER_ELEMENT, graph.getNode("work").getExecutionMode());
		assertEquals(ExecutionMode.PER_ELEMENT, graph.getNode("score").getExecutionMode());

		assertEquals(ExecutionMode.SINGLE, graph.getNode("gather").getExecutionMode(),
			"A gather is not a special node type - it is a node whose MANY inputs happen to have "
				+ "settled into a sequence, so it runs once with the lot");
	}

	@Test
	void testPerElementNodesNameTheDriverTheyFanOutFrom() {
		PipelineGraph graph = graph();

		assertEquals("split", graph.getNode("work").getFanOutDriver());
		assertEquals("split", graph.getNode("score").getFanOutDriver());
		assertNull(graph.getNode("split").getFanOutDriver(), "The driver does not fan out from itself");
		assertNull(graph.getNode("gather").getFanOutDriver(), "The gather is back to once per item");
	}

	// ── Options: the documented key and the legacy alias ──────────────────

	@Test
	void testOptionsAreCarriedAndNormalisedToPlainCollections() {
		Map<String, Object> options = graph().getNode("split").getOptions();

		assertEquals(512, options.get("chunkSize"));
		assertEquals(32, options.get("overlap"));

		// Every NodePortResolver lives in node-model, which has no Vert.x on its classpath. A
		// JsonArray here resolved no ports at all and the edge drawn to them failed at boot.
		Object labels = options.get("labels");
		assertInstanceOf(List.class, labels, "Nested arrays must arrive as java.util.List");
		assertEquals(List.of("intro", "body"), labels);
	}

	/**
	 * {@code config} is what the pipeline editor used to write. Definitions saved then still carry
	 * it, and dropping it silently is what kept node parameters from ever reaching a worker.
	 */
	@Test
	void testTheLegacyConfigAliasStillLoads() {
		Map<String, Object> options = graph().getNode("work").getOptions();

		assertEquals("legacy-alias", options.get("model"));
		assertEquals(0.5, options.get("temperature"));
	}

	@Test
	void testOptionsWinsWhenBothKeysArePresent() {
		// options is the shape the editor writes now, so a definition carrying both was
		// round-tripped through the rename and the newer key is the live one.
		assertEquals("documented", graph().getNode("score").getOptions().get("scale"));
	}

	// ── Per-node flags ────────────────────────────────────────────────────

	@Test
	void testAffinityGroupsAreRead() {
		PipelineGraph graph = graph();

		assertEquals("gpu", graph.getNode("work").getAffinity());
		assertEquals("gpu", graph.getNode("score").getAffinity());
		assertEquals(PipelineGraphNode.DEFAULT_AFFINITY, graph.getNode("split").getAffinity(),
			"A node that declares no affinity falls into the one default group");
	}

	@Test
	void testBlockingAndSyncToLoomAreReadPerNode() {
		PipelineGraph graph = graph();

		assertTrue(graph.getNode("split").isBlocking(), "blocking defaults to true - failing open hides errors");
		assertFalse(graph.getNode("notice").isBlocking(), "and the fixture opts one node out explicitly");

		assertFalse(graph.getNode("split").isSyncToLoom(), "syncToLoom defaults to false");
		assertTrue(graph.getNode("work").isSyncToLoom());
	}

	@Test
	void testOnlyTheSourceNodeIsMarkedAsOne() {
		PipelineGraph graph = graph();

		assertTrue(graph.getNode("src").isSource());
		for (String id : List.of("route", "notice", "split", "work", "score", "gather")) {
			assertFalse(graph.getNode(id).isSource(), id + " must not be a source");
		}
	}

	// ── Demanded outputs ──────────────────────────────────────────────────

	/**
	 * What a node is asked to produce, derived from the edges leaving it. A worker uses this to skip
	 * producing what nobody consumes, so an over-broad set costs real work per item.
	 */
	@Test
	void testDemandedOutputsAreOnlyThePortsSomebodyConsumes() {
		PipelineGraph graph = graph();

		assertEquals(Set.of("media"), graph.getNode("src").getDemandedOutputs());
		assertEquals(Set.of("a", "label"), graph.getNode("route").getDemandedOutputs(),
			"route.b and route.verdict are wired to nothing and must not be demanded");
		assertEquals(Set.of("texts"), graph.getNode("split").getDemandedOutputs(),
			"split.count is wired to nothing");
		assertEquals(Set.of("result"), graph.getNode("work").getDemandedOutputs());
		assertEquals(Set.of("score"), graph.getNode("score").getDemandedOutputs());
		assertEquals(Set.of(), graph.getNode("gather").getDemandedOutputs(), "Nothing consumes the report");
	}

	// ── Pipeline-wide settings ────────────────────────────────────────────

	@Test
	void testPipelineWideSettingsAreRead() {
		PipelineGraph graph = graph();

		assertEquals(25, graph.getResultBatchSize());
		assertTrue(graph.isReuseResults());
	}
}
