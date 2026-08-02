package io.metaloom.loom.nodes.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;

/**
 * Covers the three {@link NodePortResolver} implementations.
 *
 * <p>
 * Options reach a resolver as an already-parsed {@code Map<String, Object>} straight out of a
 * pipeline definition an author typed in the editor, so the two things worth testing are the mapping
 * itself and what happens when the options are nonsense. A resolver that throws would take out the
 * whole descriptor listing - the palette would go blank because one node was misconfigured - so
 * every malformed shape must degrade to a usable port set instead.
 * </p>
 */
public class NodePortResolverTest {

	private final ScriptPortResolver script = new ScriptPortResolver();
	private final LlmPortResolver llm = new LlmPortResolver();
	private final VlmPortResolver vlm = new VlmPortResolver();
	private final FilterPortResolver filter = new FilterPortResolver();

	// ------------------------------------------------------------------ script ---

	/**
	 * The whole point of the refactor: {@code TEXT_LIST} and {@code IMAGE_LIST} used to collapse onto
	 * the same content type as their scalar counterparts ({@code data/text} and
	 * {@code data/thumbnail}), which made "I emit N of these" invisible. They are now {@code MANY},
	 * which is what lets a script fan out.
	 */
	@Test
	void testScriptListTypesBecomeMany() {
		List<PortSpec> ports = script.resolveOutputPorts(descriptor("script"),
			options("outputs", List.of(
				output("paragraphs", "TEXT_LIST"),
				output("frames", "IMAGE_LIST"))));

		assertEquals(2, ports.size());

		PortSpec paragraphs = ports.get(0);
		assertEquals("paragraphs", paragraphs.getId());
		assertEquals(ContentTypeRegistry.TEXT_PLAIN, paragraphs.getContentType());
		assertEquals(Cardinality.MANY, paragraphs.getCardinality(), "TEXT_LIST must fan out, not collapse onto TEXT");

		PortSpec frames = ports.get(1);
		assertEquals("frames", frames.getId());
		assertEquals(ContentTypeRegistry.ARTIFACT_IMAGE, frames.getContentType());
		assertEquals(Cardinality.MANY, frames.getCardinality(), "IMAGE_LIST must fan out, not collapse onto IMAGE");
	}

	/**
	 * Every declared type maps to exactly one (content type, cardinality) pair.
	 */
	@Test
	void testScriptMapsEveryDeclaredType() {
		List<Map<String, Object>> declarations = new ArrayList<>();
		String[][] cases = {
			{ "a", "STRING", ContentTypeRegistry.SCALAR_STRING, "ONE" },
			{ "b", "TEXT", ContentTypeRegistry.TEXT_PLAIN, "ONE" },
			{ "c", "INTEGER", ContentTypeRegistry.SCALAR_INTEGER, "ONE" },
			{ "d", "NUMBER", ContentTypeRegistry.SCALAR_NUMBER, "ONE" },
			{ "e", "BOOLEAN", ContentTypeRegistry.SCALAR_BOOLEAN, "ONE" },
			{ "f", "JSON", ContentTypeRegistry.STRUCT_JSON, "ONE" },
			{ "g", "TEXT_LIST", ContentTypeRegistry.TEXT_PLAIN, "MANY" },
			{ "h", "TIMEFRAMES", ContentTypeRegistry.STRUCT_SEGMENTS, "ONE" },
			{ "i", "IMAGE", ContentTypeRegistry.ARTIFACT_IMAGE, "ONE" },
			{ "j", "IMAGE_LIST", ContentTypeRegistry.ARTIFACT_IMAGE, "MANY" },
			{ "k", "PATH", ContentTypeRegistry.ARTIFACT_FILE, "ONE" }
		};
		for (String[] testCase : cases) {
			declarations.add(output(testCase[0], testCase[1]));
		}

		List<PortSpec> ports = script.resolveOutputPorts(descriptor("script"), options("outputs", declarations));
		assertEquals(cases.length, ports.size(), "resolved: " + ports);

		for (int i = 0; i < cases.length; i++) {
			PortSpec port = ports.get(i);
			assertEquals(cases[i][0], port.getId());
			assertEquals(cases[i][2], port.getContentType(), "content type for " + cases[i][1]);
			assertEquals(Cardinality.valueOf(cases[i][3]), port.getCardinality(), "cardinality for " + cases[i][1]);
			assertNotNull(port.getDescription(), "port " + port.getId() + " has no description");
		}
	}

	/**
	 * Type names are matched case-insensitively, because the option is free text in the editor.
	 */
	@Test
	void testScriptTypeNamesAreCaseInsensitive() {
		List<PortSpec> ports = script.resolveOutputPorts(descriptor("script"),
			options("outputs", List.of(output("caption", "  text  "))));

		assertEquals(1, ports.size());
		assertEquals(ContentTypeRegistry.TEXT_PLAIN, ports.get(0).getContentType());
	}

	/**
	 * A definition an author mistyped must cost only the offending port, never an exception.
	 */
	@Test
	void testScriptSurvivesMalformedOptions() {
		assertEquals(List.of(), ids(script.resolveOutputPorts(descriptor("script"), null)), "null options");
		assertEquals(List.of(), ids(script.resolveOutputPorts(descriptor("script"), Map.of())), "no outputs option");
		assertEquals(List.of(), ids(script.resolveOutputPorts(descriptor("script"), options("outputs", "not-a-list"))));
		assertEquals(List.of(), ids(script.resolveOutputPorts(descriptor("script"), options("outputs", Map.of("key", "value")))),
			"an object where an array was expected");

		List<Object> mixed = new ArrayList<>(Arrays.asList(
			null,
			"a bare string",
			Map.of("key", "good", "type", "TEXT"),
			Map.of("key", "no_type_at_all"),
			Map.of("type", "TEXT"),
			Map.of("key", "unknown_type", "type", "NOT_A_TYPE"),
			Map.of("key", "Invalid Id", "type", "TEXT"),
			Map.of("key", "good", "type", "INTEGER")));

		List<PortSpec> ports = script.resolveOutputPorts(descriptor("script"), options("outputs", mixed));

		assertEquals(List.of("good"), ids(ports), "only the single well-formed declaration survives");
		assertEquals(ContentTypeRegistry.TEXT_PLAIN, ports.get(0).getContentType(),
			"a duplicate key must not override the first declaration");
	}

	// -------------------------------------------------------------- llm and vlm ---

	@Test
	void testLlmEmitsOnePortPerPrompt() {
		Map<String, Object> prompts = new LinkedHashMap<>();
		prompts.put("summary", Map.of("model", "llama3", "prompt", "Summarise this"));
		prompts.put("topics", Map.of("model", "llama3", "prompt", "List the topics"));

		List<PortSpec> ports = llm.resolveOutputPorts(descriptor("llm"), options("prompts", prompts));

		assertEquals(List.of("result_summary", "result_topics"), ids(ports),
			"the port ids must match what the node writes at runtime");
		for (PortSpec port : ports) {
			assertEquals(ContentTypeRegistry.TEXT_PLAIN, port.getContentType());
			assertEquals(Cardinality.ONE, port.getCardinality());
			assertNotNull(port.getDescription());
		}
	}

	@Test
	void testVlmEmitsOnePortPerPrompt() {
		Map<String, Object> prompts = new LinkedHashMap<>();
		prompts.put("olmocr", Map.of("model", "allenai/olmOCR-2-7B-1025-FP8"));

		List<PortSpec> ports = vlm.resolveOutputPorts(descriptor("vlm"), options("prompts", prompts));

		assertEquals(List.of("result_olmocr"), ids(ports));
		assertEquals(ContentTypeRegistry.TEXT_PLAIN, ports.get(0).getContentType());
	}

	/**
	 * A freshly dropped node has no prompts yet. It still needs one handle, otherwise the author
	 * cannot connect anything to it and has no way to discover that prompts are what it wants.
	 */
	@Test
	void testPromptResolversFallBackToASingleResultPort() {
		for (PromptPortResolver resolver : List.of(llm, vlm)) {
			String kind = resolver.kind();
			assertEquals(List.of("result"), ids(resolver.resolveOutputPorts(descriptor(kind), Map.of())),
				kind + " with no prompts option");
			assertEquals(List.of("result"), ids(resolver.resolveOutputPorts(descriptor(kind), options("prompts", Map.of()))),
				kind + " with an empty prompt map");
			assertEquals(List.of("result"), ids(resolver.resolveOutputPorts(descriptor(kind), null)),
				kind + " with null options");
		}
	}

	@Test
	void testPromptResolversSurviveMalformedOptions() {
		assertEquals(List.of("result"), ids(llm.resolveOutputPorts(descriptor("llm"), options("prompts", "not-a-map"))));
		assertEquals(List.of("result"), ids(llm.resolveOutputPorts(descriptor("llm"), options("prompts", List.of("summary")))),
			"an array where an object was expected");

		Map<Object, Object> odd = new LinkedHashMap<>();
		odd.put("Not A Valid Id", Map.of());
		odd.put("", Map.of());
		odd.put("usable", Map.of());
		List<PortSpec> ports = llm.resolveOutputPorts(descriptor("llm"), options("prompts", odd));
		assertEquals(List.of("result_usable"), ids(ports), "prompt ids that cannot form a port id are skipped");
	}

	// ------------------------------------------------------------------ wiring ---

	/**
	 * All three resolvers must be reachable through the {@link ServiceLoader} - an unregistered
	 * resolver is a node whose handles never appear.
	 */
	@Test
	void testAllResolversAreDiscoverable() {
		List<String> kinds = new ArrayList<>();
		ServiceLoader.load(NodePortResolver.class).forEach(r -> kinds.add(r.kind()));

		for (String kind : List.of("script", "llm", "vlm", "filter")) {
			assertTrue(kinds.contains(kind),
				"no NodePortResolver registered for kind '" + kind + "'. Discovered: " + kinds);
		}
	}

	// ------------------------------------------------------------------ filter ---

	/**
	 * One selective port per bucket, in declaration order, followed by the three fixed ports.
	 */
	@Test
	void testFilterEmitsOnePortPerBucketPlusTheFixedThree() {
		List<PortSpec> ports = filter.resolveOutputPorts(descriptor("filter"),
			options("buckets", List.of(bucket("de", "German"), bucket("en", "English"))));

		assertEquals(List.of("de", "en", "other", "passed", "bucket"), ids(ports));

		assertEquals("German", ports.get(0).getLabel());
		assertEquals(ContentTypeRegistry.MEDIA_ANY, ports.get(0).getContentType());
		assertTrue(ports.get(0).isSelective(), "a bucket port routes");
		assertTrue(ports.get(2).isSelective(), "'other' routes too - it is the branch nothing else matched");

		assertEquals(ContentTypeRegistry.CONTROL_FILTER, ports.get(3).getContentType());
		assertFalse(ports.get(3).isSelective(), "'passed' fires for every item and must never gate a consumer");
		assertEquals(ContentTypeRegistry.SCALAR_STRING, ports.get(4).getContentType());
		assertFalse(ports.get(4).isSelective(), "'bucket' fires for every item and must never gate a consumer");
	}

	/**
	 * A node with no buckets configured yet is still connectable - the same reason
	 * {@link PromptPortResolver} falls back to a single {@code result} port. Dropping a filter onto
	 * the canvas and finding no handles at all would leave the author with no way forward.
	 */
	@Test
	void testFilterWithoutBucketsStillOffersTheFixedPorts() {
		assertEquals(List.of("other", "passed", "bucket"), ids(filter.resolveOutputPorts(descriptor("filter"), Map.of())));
		assertEquals(List.of("other", "passed", "bucket"), ids(filter.resolveOutputPorts(descriptor("filter"), options("buckets", List.of()))));
	}

	/**
	 * Every way an author can get a bucket wrong degrades to "that port does not exist", never to an
	 * exception. A half-typed row is the normal state of the editor while someone is typing in it.
	 */
	@Test
	void testFilterSkipsMalformedBuckets() {
		List<Object> declared = new ArrayList<>(Arrays.asList(
			bucket("de", "German"),
			bucket("de", "Duplicate"),       // duplicate id
			bucket("", null),                // blank id - a freshly added row
			bucket("Not A Port", null),      // fails ID_PATTERN
			bucket("other", null),           // reserved: the catch-all
			bucket("passed", null),          // reserved: the verdict
			bucket("media", null),           // reserved: collides with an input
			"not-an-object",
			null));

		assertEquals(List.of("de", "other", "passed", "bucket"), ids(filter.resolveOutputPorts(descriptor("filter"), options("buckets", declared))));
	}

	/**
	 * A bucket without a label is named after its id rather than rendering an empty handle caption.
	 */
	@Test
	void testFilterBucketWithoutALabelFallsBackToItsId() {
		List<PortSpec> ports = filter.resolveOutputPorts(descriptor("filter"), options("buckets", List.of(bucket("ja", null))));

		assertEquals("ja", ports.get(0).getId());
		assertEquals("ja", ports.get(0).getLabel());
	}

	/**
	 * The option arriving as something other than a list must not take the node's fixed ports with it.
	 */
	@Test
	void testFilterToleratesANonListBucketsOption() {
		assertEquals(List.of("other", "passed", "bucket"), ids(filter.resolveOutputPorts(descriptor("filter"), options("buckets", "de,en"))));
	}

	/**
	 * Input ports are static for all three kinds, so the default must hand back the descriptor's own.
	 */
	@Test
	void testInputPortsDefaultToTheDescriptor() {
		NodeDescriptor descriptor = descriptor("script")
			.setInputPorts(List.of(PortSpec.optionalOne("media", ContentTypeRegistry.MEDIA_ANY)));

		assertEquals(List.of("media"), ids(script.resolveInputPorts(descriptor, Map.of())));
	}

	// ------------------------------------------------------------------ helpers ---

	private static NodeDescriptor descriptor(String kind) {
		return new NodeDescriptor().setKind(kind).setDynamicPorts(true);
	}

	private static Map<String, Object> options(String key, Object value) {
		Map<String, Object> options = new LinkedHashMap<>();
		options.put(key, value);
		return options;
	}

	/** A bucket row as the editor writes it. A null label is the shape of a row someone has not finished. */
	private static Map<String, Object> bucket(String id, String label) {
		Map<String, Object> declaration = new LinkedHashMap<>();
		declaration.put("id", id);
		if (label != null) {
			declaration.put("label", label);
		}
		return declaration;
	}

	private static Map<String, Object> output(String key, String type) {
		Map<String, Object> declaration = new LinkedHashMap<>();
		declaration.put("key", key);
		declaration.put("type", type);
		return declaration;
	}

	private static List<String> ids(List<PortSpec> ports) {
		return ports.stream().map(PortSpec::getId).toList();
	}
}
