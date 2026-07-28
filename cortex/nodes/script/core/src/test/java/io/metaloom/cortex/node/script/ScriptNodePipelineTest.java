package io.metaloom.cortex.node.script;

import static io.metaloom.cortex.pipeline.test.assertj.PipelineAssertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.script.engine.ScriptEngine;
import io.metaloom.cortex.node.script.engine.js.GraalJsScriptEngine;
import io.metaloom.cortex.pipeline.api.PipelineResult;
import io.metaloom.cortex.pipeline.api.event.NodeCompletionEvent;
import io.metaloom.cortex.pipeline.api.event.PipelineTrackingEvent;
import io.metaloom.cortex.pipeline.core.node.CortexNodeAdapter;
import io.metaloom.cortex.pipeline.test.AbstractNodeChainTest;
import io.metaloom.cortex.pipeline.test.CapturingNode;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * {@link ScriptNode} inside a real pipeline DAG: adapter integration, event dispatch, and output
 * chaining into a downstream node.
 *
 * <p>
 * The script is <em>not</em> stubbed here - unlike the sidecar-backed nodes there is nothing
 * expensive to avoid, and running the real engine is what proves a script's outputs actually reach
 * a downstream node through the adapter.
 * </p>
 */
class ScriptNodePipelineTest extends AbstractNodeChainTest {

	@TempDir
	File tempDir;

	private StubLoomMedia media;

	@BeforeEach
	void setUpTestData() throws IOException {
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
	}

	private CortexNodeAdapter createAdapter(String script, JsonArray outputs) throws Exception {
		return createAdapter(script, outputs, true);
	}

	private CortexNodeAdapter createAdapter(String script, JsonArray outputs, boolean enabled) throws Exception {
		Map<String, Provider<ScriptEngine>> engines = Map.of(GraalJsScriptEngine.ID, GraalJsScriptEngine::new);
		ScriptNodeOptions options = new ScriptNodeOptions();
		options.setEnabled(enabled);

		ScriptNode node = new ScriptNode(null, new CortexOptions().setMetaPath(tempDir.toPath()), options, engines);
		node.configure(new JsonObject()
			.put("id", ScriptNode.KIND)
			.put("type", ScriptNode.KIND)
			.put("script", script)
			.put("outputs", outputs));
		return adapt(node);
	}

	private static JsonArray outputs(String key, String type) {
		return new JsonArray().add(new JsonObject().put("key", key).put("type", type));
	}

	@Test
	void testScriptRunsInAPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter("out.text('caption', 'a red car');", outputs("caption", "TEXT"));

		PipelineResult result = execute(media, adapter);

		assertThat(result)
			.isSuccess()
			.hasCompletedNode(ScriptNode.KIND)
			.hasNodeOutput(ScriptNode.KIND, "caption", "a red car");
	}

	@Test
	void testCompletionEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter("out.text('caption', 'x');", outputs("caption", "TEXT"));

		execute(media, adapter);

		NodeCompletionEvent event = assertCompletionEvent(ScriptNode.KIND);
		assertThat(event.getResult().getState()).isEqualTo(ResultState.SUCCESS);
	}

	@Test
	void testTrackingEventsDispatched() throws Exception {
		CortexNodeAdapter adapter = createAdapter("out.text('caption', 'x');", outputs("caption", "TEXT"));

		execute(media, adapter);

		assertTrackingEvent(ScriptNode.KIND, PipelineTrackingEvent.Type.NODE_STARTED);
		assertTrackingEvent(ScriptNode.KIND, PipelineTrackingEvent.Type.NODE_COMPLETED);
	}

	@Test
	void testOutputChaining() throws Exception {
		CortexNodeAdapter adapter = createAdapter("out.text('caption', 'chained');", outputs("caption", "TEXT"));
		CapturingNode consumer = new CapturingNode("consumer", ScriptNode.KIND, "caption");

		PipelineResult result = execute(media, adapter, consumer);

		assertThat(result).isSuccess().hasNodeCount(3);
		assertThat(consumer.capturedValues()).containsExactly("chained");
	}

	@Test
	void testMediaFacadeIsVisibleToTheScript() throws Exception {
		CortexNodeAdapter adapter = createAdapter("out.text('kind', media.isVideo ? 'video' : 'other');", outputs("kind", "TEXT"));

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess().hasNodeOutput(ScriptNode.KIND, "kind", "video");
	}

	@Test
	void testDisabledNode() throws Exception {
		CortexNodeAdapter adapter = createAdapter("out.text('caption', 'x');", outputs("caption", "TEXT"), false);

		PipelineResult result = execute(media, adapter);

		assertThat(result).isSuccess();
		assertThat(result).node(ScriptNode.KIND).isSkipped();
	}

	@Test
	void testDryRunPipeline() throws Exception {
		CortexNodeAdapter adapter = createAdapter("out.text('caption', 'x');", outputs("caption", "TEXT"));

		PipelineResult result = executeDryRun(media, adapter);

		assertThat(result).isDryRun();
		assertThat(result).node(ScriptNode.KIND).isSkipped();
	}
}
