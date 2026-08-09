package io.metaloom.loom.rest.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.rest.validation.ValidationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The ad-hoc definition builder.
 *
 * <p>
 * These assertions are about one thing: an ad-hoc run must process the assets the caller named and
 * nothing else. Everything the builder rejects, it rejects because accepting it would quietly change
 * <em>what</em> got processed.
 * </p>
 */
public class AdHocGraphBuilderTest {

	@Test
	public void testSingleNodeDefinitionWiresTheSourceToTheNode() {
		JsonObject definition = AdHocGraphBuilder.singleNodeDefinition("vlm", Map.of("prompt", "what is this?"));

		JsonArray nodes = definition.getJsonArray("nodes");
		assertThat(nodes).hasSize(2);
		assertThat(nodes.getJsonObject(0).getString("type")).isEqualTo(AdHocGraphBuilder.SOURCE_KIND);
		assertThat(nodes.getJsonObject(0).getBoolean("source")).isTrue();
		assertThat(nodes.getJsonObject(1).getString("type")).isEqualTo("vlm");
		assertThat(nodes.getJsonObject(1).getJsonObject("options").getString("prompt")).isEqualTo("what is this?");

		JsonObject edge = definition.getJsonArray("edges").getJsonObject(0);
		assertThat(edge.getString("source")).isEqualTo(AdHocGraphBuilder.SOURCE_NODE_ID);
		assertThat(edge.getString("sourcePort")).isEqualTo(AdHocGraphBuilder.MEDIA_PORT);
		assertThat(edge.getString("target")).isEqualTo("vlm");
		assertThat(edge.getString("targetPort")).isEqualTo(AdHocGraphBuilder.MEDIA_PORT);
	}

	@Test
	public void testSingleNodeDefinitionOmitsEmptyOptions() {
		JsonObject definition = AdHocGraphBuilder.singleNodeDefinition("sha512", Map.of());
		assertThat(definition.getJsonArray("nodes").getJsonObject(1).containsKey("options")).isFalse();
	}

	@Test
	public void testSourceIsPrependedAndWiredToEveryRoot() {
		JsonObject submitted = new JsonObject()
			.put("version", 1)
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "a").put("type", "sha512"))
				.add(new JsonObject().put("id", "b").put("type", "vlm"))
				.add(new JsonObject().put("id", "c").put("type", "translate")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "e1").put("source", "b").put("target", "c")));

		JsonObject result = AdHocGraphBuilder.withLoomFetchSource(submitted);

		assertThat(result.getJsonArray("nodes").getJsonObject(0).getString("type")).isEqualTo(AdHocGraphBuilder.SOURCE_KIND);
		// a and b have no inbound edge and therefore need the media; c is fed by b and must not be
		// wired to the source as well, or it would run twice with different inputs.
		JsonArray edges = result.getJsonArray("edges");
		assertThat(edges).hasSize(3);
		assertThat(targetsOfSource(edges)).containsExactlyInAnyOrder("a", "b");
	}

	@Test
	public void testAnExistingLoomFetchSourceIsLeftAlone() {
		JsonObject submitted = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "src").put("type", AdHocGraphBuilder.SOURCE_KIND).put("source", true))
				.add(new JsonObject().put("id", "a").put("type", "sha512")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "e1").put("source", "src").put("target", "a")));

		assertThat(AdHocGraphBuilder.withLoomFetchSource(submitted)).isSameAs(submitted);
	}

	@Test
	public void testAForeignSourceIsRejected() {
		JsonObject submitted = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "scan").put("type", "filesystem-source").put("source", true))
				.add(new JsonObject().put("id", "a").put("type", "sha512")));

		// A filesystem source would enumerate a second, unrelated set of media on a worker, so the run
		// would silently process something other than the assets the caller named.
		assertThatThrownBy(() -> AdHocGraphBuilder.withLoomFetchSource(submitted))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("filesystem-source")
			.hasMessageContaining(AdHocGraphBuilder.SOURCE_KIND);
	}

	@Test
	public void testAGraphWithNoRootIsRejected() {
		JsonObject submitted = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", "a").put("type", "sha512"))
				.add(new JsonObject().put("id", "b").put("type", "vlm")))
			.put("edges", new JsonArray()
				.add(new JsonObject().put("id", "e1").put("source", "a").put("target", "b"))
				.add(new JsonObject().put("id", "e2").put("source", "b").put("target", "a")));

		assertThatThrownBy(() -> AdHocGraphBuilder.withLoomFetchSource(submitted))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("inbound edge");
	}

	@Test
	public void testTheReservedSourceIdIsRejected() {
		JsonObject submitted = new JsonObject()
			.put("nodes", new JsonArray()
				.add(new JsonObject().put("id", AdHocGraphBuilder.SOURCE_NODE_ID).put("type", "sha512")));

		assertThatThrownBy(() -> AdHocGraphBuilder.withLoomFetchSource(submitted))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("reserved");
	}

	@Test
	public void testEmptyDefinitionsAreRejected() {
		assertThatThrownBy(() -> AdHocGraphBuilder.withLoomFetchSource(null))
			.isInstanceOf(ValidationException.class);
		assertThatThrownBy(() -> AdHocGraphBuilder.withLoomFetchSource(new JsonObject()))
			.isInstanceOf(ValidationException.class)
			.hasMessageContaining("no nodes");
		assertThatThrownBy(() -> AdHocGraphBuilder.withLoomFetchSource(
			new JsonObject().put("nodes", new JsonArray().add(new JsonObject().put("type", "sha512")))))
				.isInstanceOf(ValidationException.class)
				.hasMessageContaining("id");
	}

	@Test
	public void testNodeIdsAreSanitisedToTheValidatorsPattern() {
		assertThat(AdHocGraphBuilder.nodeIdFor("sha512")).isEqualTo("sha512");
		assertThat(AdHocGraphBuilder.nodeIdFor("image-manipulation")).isEqualTo("image-manipulation");
		assertThat(AdHocGraphBuilder.nodeIdFor("My_Node")).isEqualTo("my-node");
		// Nothing usable survived; the kind is still carried by the node's type, so the run stays
		// readable rather than failing on an id the validator would reject anyway.
		assertThat(AdHocGraphBuilder.nodeIdFor("---")).isEqualTo("node");
		assertThat(AdHocGraphBuilder.nodeIdFor(null)).isEqualTo("node");
	}

	private static java.util.List<String> targetsOfSource(JsonArray edges) {
		return edges.stream()
			.map(JsonObject.class::cast)
			.filter(edge -> AdHocGraphBuilder.SOURCE_NODE_ID.equals(edge.getString("source")))
			.map(edge -> edge.getString("target"))
			.toList();
	}

}
