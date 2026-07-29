package io.metaloom.loom.pipeline.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Regression guard for the second defect that broke node configuration end to end.
 *
 * <p>
 * The pipeline editor serialised per-node parameters into a {@code config} object while this
 * parser only ever read {@code options}. {@code "config"} appeared in no Java parser, so every
 * parameter an author set in the editor was dropped at the Loom boundary and never reached a
 * worker - and, because no node read per-instance options anyway, nothing failed loudly enough to
 * notice. The editor now writes {@code options}; {@code config} stays readable so definitions
 * saved before the fix keep loading.
 * </p>
 */
public class PipelineNodeOptionsParsingTest {

	private final PipelineGraphParser parser = new PipelineGraphParser();

	private static JsonObject definitionWith(JsonObject scriptNode) {
		return new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "pn1").put("type", "filesystem-source").put("source", true))
				.add(scriptNode))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "pe1").put("source", "pn1").put("sourcePort", "media").put("target", "pn2").put("targetPort", "media")));
	}

	private static JsonObject optionsBag() {
		return new JsonObject()
			.put("engine", "js")
			.put("script", "out.text('caption', 'x');")
			.put("outputs", new JsonArray().add(new JsonObject().put("key", "caption").put("type", "TEXT")));
	}

	private Map<String, Object> parseNodeOptions(JsonObject scriptNode) {
		PipelineGraph graph = parser.parse("demo", definitionWith(scriptNode), true, false, 100);
		return graph.getNode("pn2").getOptions();
	}

	@Test
	void shouldReadOptionsFromTheOptionsKey() {
		Map<String, Object> options = parseNodeOptions(
			new JsonObject().put("id", "pn2").put("type", "script").put("options", optionsBag()));

		assertEquals("js", options.get("engine"));
		assertEquals("out.text('caption', 'x');", options.get("script"));
		assertTrue(options.containsKey("outputs"), "the declared outputs must survive the parse");
	}

	@Test
	void shouldReadOptionsFromTheLegacyConfigKey() {
		Map<String, Object> options = parseNodeOptions(
			new JsonObject().put("id", "pn2").put("type", "script").put("config", optionsBag()));

		assertEquals("js", options.get("engine"),
			"definitions saved by the editor before the options fix must keep loading");
	}

	@Test
	void shouldPreferOptionsWhenBothArePresent() {
		JsonObject node = new JsonObject().put("id", "pn2").put("type", "script")
			.put("options", optionsBag().put("engine", "js"))
			.put("config", optionsBag().put("engine", "stale"));

		assertEquals("js", parseNodeOptions(node).get("engine"),
			"`options` is the shape the editor writes now, so it wins");
	}

	@Test
	void shouldYieldAnEmptyBagWhenNeitherIsPresent() {
		Map<String, Object> options = parseNodeOptions(new JsonObject().put("id", "pn2").put("type", "script"));
		assertTrue(options.isEmpty());
	}
}
