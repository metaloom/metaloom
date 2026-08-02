package io.metaloom.loom.pipeline.graph;

import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.HASH_MD5;
import static io.metaloom.loom.nodes.spec.ContentTypeRegistry.MEDIA_ANY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.pipeline.TestDescriptors;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Port checking and fan-out classification, as the parser applies them.
 *
 * <p>Everything here is decided <em>before</em> a run starts, which is the point: a graph that
 * cannot work should be refused while its author is looking at it, not halfway through a scan of
 * ten thousand assets. The two shapes v1 deliberately does not support — nested fan-out and a zip
 * across two unrelated sequences — are rejected here rather than producing a run that quietly
 * mismatches elements.</p>
 *
 * <p>The descriptors are synthetic on purpose. Real node kinds do not offer every combination the
 * analyzer has to decide about (nothing shipping today declares a {@code detection/* ONE} input),
 * and pinning the rules to whichever real node happens to have the right shape would make these
 * tests break whenever that node is re-specified.</p>
 */
public class PortGraphAnalyzerTest {

	private final PipelineGraphParser parser = new PipelineGraphParser(TestDescriptors.registry());

	private static JsonObject node(String id, String kind) {
		return new JsonObject().put("id", id).put("type", kind);
	}

	private static JsonObject edge(String from, String sourcePort, String to, String targetPort) {
		return new JsonObject().put("source", from).put("sourcePort", sourcePort)
			.put("target", to).put("targetPort", targetPort);
	}

	private PipelineGraph parse(JsonArray nodes, JsonArray edges) {
		nodes.add(0, node("src", "test-source").put("source", true));
		return parser.parse("ports", new JsonObject().put("nodes", nodes).put("edges", edges), true, false, 0);
	}

	private GraphValidationException rejected(JsonArray nodes, JsonArray edges) {
		return assertThrows(GraphValidationException.class, () -> parse(nodes, edges));
	}

	// ---------------------------------------------------------------- wiring rules

	@Test
	void testATypeMismatchIsRejected() {
		GraphValidationException e = rejected(
			new JsonArray().add(node("hash", "hasher")),
			new JsonArray().add(edge("src", "media", "hash", "digest")));

		// Assignability never crosses families, so media into a hash port cannot be a
		// provisional wildcard match - it is simply wrong.
		assertTrue(e.getMessage().contains("incompatible content types"), e.getMessage());
		assertTrue(e.getMessage().contains(MEDIA_ANY) && e.getMessage().contains(HASH_MD5), e.getMessage());
	}

	@Test
	void testAWildcardProducerIntoASubtypeIsAccepted() {
		// A source cannot know the concrete mime when the graph is drawn, so media/* into
		// media/audio is provisionally valid and settled at runtime with the file in hand.
		PipelineGraph graph = parse(
			new JsonArray().add(node("either", "either")),
			new JsonArray().add(edge("src", "media", "either", "audio")));

		assertEquals(2, graph.size());
	}

	@Test
	void testAnUnknownTargetPortIsRejected() {
		GraphValidationException e = rejected(
			new JsonArray().add(node("w", "worker")),
			new JsonArray().add(edge("src", "media", "w", "nonexistent")));

		assertTrue(e.getMessage().contains("no input port 'nonexistent'"), e.getMessage());
	}

	@Test
	void testAnUnknownSourcePortIsRejected() {
		GraphValidationException e = rejected(
			new JsonArray().add(node("d", "describe")),
			new JsonArray().add(edge("src", "nonexistent", "d", "media")));

		assertTrue(e.getMessage().contains("no output port 'nonexistent'"), e.getMessage());
	}

	@Test
	void testAnUnsatisfiedRequiredInputIsRejected() {
		// 'worker' declares text as required and nothing feeds it. Letting this run would
		// dispatch a task whose input port is simply absent.
		GraphValidationException e = rejected(
			new JsonArray().add(node("d", "describe")).add(node("w", "worker")),
			new JsonArray().add(edge("src", "media", "d", "media")));

		assertTrue(e.getMessage().contains("requires input 'text'"), e.getMessage());
	}

	// ---------------------------------------------------------------- XOR groups

	@Test
	void testARequiredXorGroupWithNothingWiredIsRejected() {
		GraphValidationException e = rejected(
			new JsonArray().add(node("e", "either")),
			new JsonArray());

		assertTrue(e.getMessage().contains("requires one of [audio, video]"), e.getMessage());
	}

	@Test
	void testAXorGroupWithTwoAlternativesWiredIsRejected() {
		GraphValidationException e = rejected(
			new JsonArray().add(node("e", "either")),
			new JsonArray()
				.add(edge("src", "media", "e", "audio"))
				.add(edge("src", "media", "e", "video")));

		// These were never two inputs; they are one input with two alternatives, and
		// wiring both leaves the node with no way to say which it should read.
		assertTrue(e.getMessage().contains("accepts only one of [audio, video]"), e.getMessage());
	}

	@Test
	void testAnOptionalXorGroupStillRejectsTwoWiredAlternatives() {
		GraphValidationException e = rejected(
			new JsonArray().add(node("e", "either-optional")),
			new JsonArray()
				.add(edge("src", "media", "e", "audio"))
				.add(edge("src", "media", "e", "video")));

		assertTrue(e.getMessage().contains("accepts only one of"), e.getMessage());
	}

	@Test
	void testAnOptionalXorGroupWithNothingWiredIsAccepted() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("e", "either-optional")),
			new JsonArray());

		// Only the required flavour insists on a member; 'at most one' is the other half
		// of the rule and must not be conflated with it.
		assertEquals(2, graph.size());
	}

	@Test
	void testExactlyOneWiredAlternativeIsAccepted() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("e", "either")),
			new JsonArray().add(edge("src", "media", "e", "video")));

		assertEquals(List.of("src"), graph.getNode("e").getDependencies());
	}

	// ---------------------------------------------------------------- multi-edge

	@Test
	void testTwoEdgesIntoASingleElementInputAreRejected() {
		GraphValidationException e = rejected(
			new JsonArray()
				.add(node("d1", "describe")).add(node("d2", "describe")).add(node("w", "worker")),
			new JsonArray()
				.add(edge("src", "media", "d1", "media"))
				.add(edge("src", "media", "d2", "media"))
				.add(edge("d1", "text", "w", "text"))
				.add(edge("d2", "text", "w", "text")));

		// A ONE port has room for one value. Silently keeping the last edge would make the
		// pipeline's behaviour depend on the order the author drew it in.
		assertTrue(e.getMessage().contains("takes one element but has 2 incoming edges"), e.getMessage());
	}

	@Test
	void testTwoEdgesIntoASequenceInputAreAccepted() {
		PipelineGraph graph = parse(
			new JsonArray()
				.add(node("d1", "describe")).add(node("d2", "describe")).add(node("c", "collector")),
			new JsonArray()
				.add(edge("src", "media", "d1", "media"))
				.add(edge("src", "media", "d2", "media"))
				.add(edge("d1", "text", "c", "items"))
				.add(edge("d2", "text", "c", "items")));

		// Elements concatenate per origin, edge by edge.
		assertEquals(2, graph.getNode("c").getInputBindings().size());
		assertEquals(List.of("d1", "d2"), graph.getNode("c").getDependencies());
	}

	// ---------------------------------------------------------------- execution mode

	@Test
	void testAPlainChainIsSingleThroughout() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("d", "describe")).add(node("w", "worker")),
			new JsonArray()
				.add(edge("src", "media", "d", "media"))
				.add(edge("d", "text", "w", "text")));

		for (String nodeId : graph.getTopologicalOrder()) {
			assertEquals(ExecutionMode.SINGLE, graph.getNode(nodeId).getExecutionMode(),
				"Nothing fans out, so every node runs once per item: " + nodeId);
		}
	}

	@Test
	void testASingleElementInputFedByASequenceRunsPerElement() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("split", "splitter")).add(node("w", "worker")),
			new JsonArray()
				.add(edge("src", "media", "split", "media"))
				.add(edge("split", "texts", "w", "text")));

		assertEquals(ExecutionMode.SINGLE, graph.getNode("split").getExecutionMode(),
			"The driver itself still runs once - it is what produces the sequence");
		assertEquals(ExecutionMode.PER_ELEMENT, graph.getNode("w").getExecutionMode());
		assertEquals("split", graph.getNode("w").getFanOutDriver());
	}

	@Test
	void testASequenceInputConsumingTheSameOutputStaysSingle() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("split", "splitter")).add(node("c", "collector")),
			new JsonArray()
				.add(edge("src", "media", "split", "media"))
				.add(edge("split", "texts", "c", "items")));

		// This branch is the entire implicit join: a MANY input gathers the sequence
		// instead of iterating it, so the node runs once and sees all of it.
		assertEquals(ExecutionMode.SINGLE, graph.getNode("c").getExecutionMode());
		assertEquals(null, graph.getNode("c").getFanOutDriver());
	}

	@Test
	void testAPerElementNodeMakesItsOwnOutputsASequence() {
		// split -> w (per element) -> c. 'w' declares a ONE output, but one element per
		// execution is still a sequence seen from further downstream, so 'c' gathers.
		PipelineGraph graph = parse(
			new JsonArray().add(node("split", "splitter")).add(node("w", "worker")).add(node("c", "collector")),
			new JsonArray()
				.add(edge("src", "media", "split", "media"))
				.add(edge("split", "texts", "w", "text"))
				.add(edge("w", "result", "c", "items")));

		assertEquals(ExecutionMode.PER_ELEMENT, graph.getNode("w").getExecutionMode());
		assertEquals(ExecutionMode.SINGLE, graph.getNode("c").getExecutionMode());
	}

	@Test
	void testNestedFanOutIsRejected() {
		GraphValidationException e = rejected(
			new JsonArray()
				.add(node("split", "splitter")).add(node("again", "resplitter")).add(node("w", "worker")),
			new JsonArray()
				.add(edge("src", "media", "split", "media"))
				.add(edge("split", "texts", "again", "text"))
				.add(edge("again", "parts", "w", "text")));

		// A single integer seq cannot address a sequence inside a sequence. Deferred, not
		// designed away - lifting it later means seqPath: int[].
		assertTrue(e.getMessage().contains("Nested fan-out is not supported"), e.getMessage());
		assertTrue(e.getMessage().contains("again"), e.getMessage());
	}

	@Test
	void testZippingTwoUnrelatedSequencesIsRejected() {
		GraphValidationException e = rejected(
			new JsonArray()
				.add(node("s1", "splitter")).add(node("s2", "splitter")).add(node("z", "zipper")),
			new JsonArray()
				.add(edge("src", "media", "s1", "media"))
				.add(edge("src", "media", "s2", "media"))
				.add(edge("s1", "texts", "z", "left"))
				.add(edge("s2", "texts", "z", "right")));

		// Element 2 of one sequence has no relationship to element 2 of another; pairing
		// them by index would invent a correspondence that does not exist.
		assertTrue(e.getMessage().contains("zips two unrelated sequences"), e.getMessage());
		assertTrue(e.getMessage().contains("s1") && e.getMessage().contains("s2"), e.getMessage());
	}

	@Test
	void testZippingTwoBranchesOfTheSameFanOutIsAccepted() {
		PipelineGraph graph = parse(
			new JsonArray()
				.add(node("split", "splitter"))
				.add(node("w1", "worker")).add(node("w2", "worker")).add(node("z", "zipper")),
			new JsonArray()
				.add(edge("src", "media", "split", "media"))
				.add(edge("split", "texts", "w1", "text"))
				.add(edge("split", "texts", "w2", "text"))
				.add(edge("w1", "result", "z", "left"))
				.add(edge("w2", "result", "z", "right")));

		// Both branches trace back to the same driver, so element i of one lines up with
		// element i of the other and the zip is meaningful.
		assertEquals(ExecutionMode.PER_ELEMENT, graph.getNode("z").getExecutionMode());
		assertEquals("split", graph.getNode("z").getFanOutDriver());
	}

	// ---------------------------------------------------------------- demanded outputs

	@Test
	void testOnlyWiredOutputPortsAreDemanded() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("split", "splitter")).add(node("c", "collector")),
			new JsonArray()
				.add(edge("src", "media", "split", "media"))
				.add(edge("split", "texts", "c", "items")));

		// 'count' has no outgoing edge, so the worker may skip computing it.
		assertEquals(Set.of("texts"), graph.getNode("split").getDemandedOutputs());
	}

	// ---------------------------------------------------------------- routing stamps

	private static InputBinding bindingOf(PipelineGraph graph, String nodeId, String targetPort) {
		return graph.getNode(nodeId).getInputBindings().stream()
			.filter(b -> b.targetPortId().equals(targetPort))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no binding on " + nodeId + "." + targetPort));
	}

	/**
	 * An edge leaving a selective port is stamped both {@code sourceSelective} — this is where the
	 * branch is decided, which is what the segmenter keys off — and {@code routed}.
	 */
	@Test
	void testAnEdgeFromASelectivePortIsStampedRouted() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("r", "router")).add(node("d", "describe")).add(node("n", "noticer")),
			new JsonArray()
				.add(edge("src", "media", "r", "media"))
				.add(edge("r", "a", "d", "media"))
				.add(edge("r", "label", "n", "label")));

		InputBinding routed = bindingOf(graph, "d", "media");
		assertTrue(routed.sourceSelective(), "'a' declares itself selective");
		assertTrue(routed.routed());

		InputBinding plain = bindingOf(graph, "n", "label");
		assertFalse(plain.sourceSelective(), "'label' carries a value for every item");
		assertFalse(plain.routed());
	}

	/**
	 * Routing is inherited down the graph, but {@code sourceSelective} is not.
	 *
	 * <p>
	 * The distinction is the whole reason there are two flags. {@code routed} has to reach the
	 * grandchild, or a branch that did not fire leaves it running with empty inputs. {@code
	 * sourceSelective} must <em>not</em>, or the segmenter would refuse to batch anything below a
	 * router — a pure performance loss, since a closed branch already stops the whole subtree.
	 * </p>
	 */
	@Test
	void testRoutingIsInheritedButSelectivityIsNot() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("r", "router")).add(node("d", "describe")).add(node("w", "worker")),
			new JsonArray()
				.add(edge("src", "media", "r", "media"))
				.add(edge("r", "a", "d", "media"))
				.add(edge("d", "text", "w", "text")));

		InputBinding grandchild = bindingOf(graph, "w", "text");
		assertTrue(grandchild.routed(), "'w' sits below a branch, so it must skip when that branch closes");
		assertFalse(grandchild.sourceSelective(), "'describe.text' decides nothing - it is an ordinary port");
	}

	/**
	 * A graph with no selective port anywhere stamps nothing. This is what contains the change: every
	 * pipeline that existed before routing behaves exactly as it did.
	 */
	@Test
	void testAGraphWithoutSelectivePortsIsNotRouted() {
		PipelineGraph graph = parse(
			new JsonArray().add(node("d", "describe")).add(node("w", "worker")),
			new JsonArray()
				.add(edge("src", "media", "d", "media"))
				.add(edge("d", "text", "w", "text")));

		assertFalse(bindingOf(graph, "d", "media").routed());
		assertFalse(bindingOf(graph, "w", "text").routed());
	}
}
