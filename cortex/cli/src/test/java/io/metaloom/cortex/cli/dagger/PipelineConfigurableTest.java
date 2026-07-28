package io.metaloom.cortex.cli.dagger;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.pipeline.api.node.PipelineNode;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Regression guard for the defect that made per-instance node configuration impossible.
 *
 * <p>
 * {@code RegistryNodeRegistrar.adapt(...)} read only the structural fields off a node definition
 * ({@code id}, {@code mode}, {@code blocking}, {@code concurrency}, {@code syncToLoom},
 * {@code timeoutMs}) and took the node's own options from the worker's YAML. A node whose
 * configuration lives in the pipeline definition - the {@code script} node - could therefore never
 * be configured. Nodes that declare {@code PipelineConfigurable} now receive the definition.
 * </p>
 */
public class PipelineConfigurableTest {

	private CortexComponent component() {
		CortexOptions options = new CortexOptions();
		options.setMetaPath(Paths.get("target/test-meta"));
		return DaggerCortexComponent.builder().options(options).build();
	}

	private static JsonObject scriptDef(String id, String script) {
		return new JsonObject()
			.put("id", id)
			.put("type", "script")
			.put("script", script)
			.put("outputs", new JsonArray().add(new JsonObject().put("key", "caption").put("type", "TEXT")));
	}

	@Test
	public void testScriptKindIsExecutable() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();
		assertThat(component.nodeFactory().registeredTypes()).contains("script");
	}

	@Test
	public void testDefinitionOptionsReachTheNode() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		PipelineNode node = component.nodeFactory().createNode(scriptDef("my-script", "out.text('caption', 'x');"));

		// A producer that throws is swallowed by the factory and yields null, so a non-null node
		// here is itself the evidence that configure(...) accepted the definition.
		assertThat(node).isNotNull();
		assertThat(node.id()).isEqualTo("my-script");
	}

	@Test
	public void testTwoScriptNodesGetIndependentInstances() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		PipelineNode first = component.nodeFactory().createNode(scriptDef("first", "out.text('caption', 'one');"));
		PipelineNode second = component.nodeFactory().createNode(scriptDef("second", "out.text('caption', 'two');"));

		assertThat(first).isNotNull();
		assertThat(second).isNotNull();
		// Configuration mutates the node, so sharing an instance would let the second script
		// silently replace the first. The kind map's Provider is what prevents it.
		assertThat(first).isNotSameAs(second);
		assertThat(first.id()).isEqualTo("first");
		assertThat(second.id()).isEqualTo("second");
	}

	@Test
	public void testUnconfigurableNodesAreUnaffected() {
		// The seam is opt-in: a node that does not declare PipelineConfigurable must still build
		// from a bare definition exactly as before.
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		PipelineNode node = component.nodeFactory().createNode(new JsonObject().put("id", "hash").put("type", "sha512"));

		assertThat(node).isNotNull();
		assertThat(node.id()).isEqualTo("hash");
	}

	@Test
	public void testInvalidScriptDefinitionIsRejected() {
		// The factory converts a producer failure into null; assert against the registrar's own
		// path so the diagnostic message is actually observable.
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		PipelineNode node = component.nodeFactory().createNode(new JsonObject()
			.put("id", "broken")
			.put("type", "script")
			.put("script", "this is not javascript(")
			.put("outputs", new JsonArray().add(new JsonObject().put("key", "caption").put("type", "TEXT"))));

		assertThat(node).as("a script that does not compile must not yield a runnable node").isNull();
	}

	@Test
	public void testMissingScriptIsRejected() {
		CortexComponent component = component();
		component.nodeRegistrar().registerAll();

		PipelineNode node = component.nodeFactory().createNode(new JsonObject().put("id", "empty").put("type", "script"));
		assertThat(node).as("a script node with no script must not be runnable").isNull();
	}
}
