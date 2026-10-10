package io.metaloom.cortex.node.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
import io.metaloom.cortex.api.node.NodeInputs;
import io.metaloom.cortex.api.node.NodeResult;
import io.metaloom.cortex.api.node.ResultState;
import io.metaloom.cortex.api.node.context.NodeContext;
import io.metaloom.cortex.api.option.CortexOptions;
import io.metaloom.cortex.node.script.engine.ScriptEngine;
import io.metaloom.cortex.node.script.engine.js.GraalJsScriptEngine;
import io.metaloom.cortex.pipeline.test.StubLoomMedia;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The paths on which something goes wrong <em>outside</em> the script body.
 *
 * <p>
 * {@code ScriptNodeTest} covers the script-facing contract. This covers what happens when the input
 * the node was handed, or a value the script produced, is shaped in a way the node's own plumbing did
 * not survive: each of these used to leave {@code compute} as a raw throwable rather than as a
 * {@code FAILED} {@link NodeResult}, which means no ledger row, no {@code .abort()}, and a pipeline
 * engine handed an exception by a node documented never to throw one.
 * </p>
 */
class ScriptNodeFailurePathTest {

	private static final SHA512 HASH = SHA512.fromString(
		"e7c22b994c59d9cf2b48e549b1e24666636045930d3da7c1acb299d1c3b7f931f94aae41edda2c2b207a36e10f8bcb8d45223e54878f5b316e7ce3b6bc019629");

	@TempDir
	File tempDir;

	private CortexOptions cortexOptions;
	private StubLoomMedia media;

	@BeforeEach
	void setup() throws Exception {
		cortexOptions = new CortexOptions().setMetaPath(tempDir.toPath());
		StubLoomMedia backing = StubLoomMedia.ofBytes(tempDir, "clip.mp4", "fake-video");
		media = new StubLoomMedia(backing.file().getAbsolutePath(), true, false, false, false);
		media.setSHA512(HASH);
	}

	private ScriptNode configured(JsonObject def) {
		Map<String, Provider<ScriptEngine>> engines = Map.of(GraalJsScriptEngine.ID, GraalJsScriptEngine::new);
		ScriptNode node = new ScriptNode(null, cortexOptions, new ScriptNodeOptions(), engines);
		node.configure(def);
		return node;
	}

	private static JsonObject def(String script, JsonArray outputs) {
		return new JsonObject()
			.put("id", "my-script")
			.put("type", ScriptNode.KIND)
			.put("script", script)
			.put("outputs", outputs);
	}

	private static JsonArray outputs(String key, String type) {
		return new JsonArray().add(new JsonObject().put("key", key).put("type", type));
	}

	/** Run with a raw string on the {@code struct/json} input port, bypassing any encoder. */
	private NodeResult runWithRawData(ScriptNode node, String encoded) {
		NodeContext<LoomMedia> ctx = NodeContext.create(media,
			NodeInputs.builder().input(ScriptNode.IN_DATA, encoded).build());
		return node.process(ctx);
	}

	// ----------------------------------------------------------------------------------------
	// A malformed 'data' payload
	// ----------------------------------------------------------------------------------------

	@Test
	void shouldFailWhenTheWiredDataPayloadIsNotAJsonObject() {
		ScriptNode node = configured(def("out.text('caption', 'ok');", outputs("caption", "TEXT")));

		// A struct/json edge can legitimately carry a JSON array - the node's data binding is a map,
		// so decoding one threw a Vert.x DecodeException straight out of compute().
		for (String payload : new String[] { "[1,2,3]", "\"just a string\"", "42", "not json at all", "{\"unclosed\": " }) {
			NodeResult result = runWithRawData(node, payload);
			assertEquals(ResultState.FAILED, result.getState(), "expected a failure for " + payload);
			assertThat(result.getMessage()).contains("not a JSON object");
			assertNull(result.get(new ScriptOutputSpec("caption", ScriptValueType.TEXT).port()));
		}
	}

	@Test
	void shouldStillAcceptAWellFormedDataPayload() {
		ScriptNode node = configured(def("out.text('caption', data.who);", outputs("caption", "TEXT")));
		NodeResult result = runWithRawData(node, "{\"who\":\"upstream\"}");

		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
		assertEquals("upstream", result.get(new ScriptOutputSpec("caption", ScriptValueType.TEXT).port()));
	}

	// ----------------------------------------------------------------------------------------
	// A guest value the host walk cannot follow
	// ----------------------------------------------------------------------------------------

	@Test
	void shouldFailRatherThanOverflowTheStackOnASelfReferentialGuestObject() {
		// fromGuest walks a guest value recursively. A cycle made that walk unbounded, and the
		// resulting StackOverflowError is an Error - not caught by execute()'s `catch (Exception)`,
		// so it escaped compute() entirely: no FAILED ledger row and no abort().
		ScriptNode node = configured(def("""
			const a = { name: 'x' };
			a.self = a;
			out.json('meta', a);
			""", outputs("meta", "JSON")));

		NodeResult result = node.process(NodeContext.create(media, NodeInputs.empty()));

		assertEquals(ResultState.FAILED, result.getState(), result.getMessage());
		assertThat(result.getMessage()).containsAnyOf("nests deeper", "refers back to itself");
	}

	@Test
	void shouldFailRatherThanOverflowTheStackOnADeeplyNestedGuestArray() {
		ScriptNode node = configured(def("""
			let a = [];
			for (let i = 0; i < 500; i++) { a = [a]; }
			out.json('meta', { deep: a });
			""", outputs("meta", "JSON")));

		NodeResult result = node.process(NodeContext.create(media, NodeInputs.empty()));
		assertEquals(ResultState.FAILED, result.getState(), result.getMessage());
		assertThat(result.getMessage()).contains("nests deeper");
	}

	@Test
	void shouldStillAcceptANormallyNestedGuestObject() {
		// The bound must be far above any legitimate shape, or it becomes the bug.
		ScriptNode node = configured(def("""
			out.json('meta', { a: { b: { c: [ { d: 'deep enough' } ] } } });
			""", outputs("meta", "JSON")));

		NodeResult result = node.process(NodeContext.create(media, NodeInputs.empty()));
		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
	}

	// ----------------------------------------------------------------------------------------
	// maxOutputBytes
	// ----------------------------------------------------------------------------------------

	@Test
	void shouldMeasureMaxOutputBytesInUtf8BytesRatherThanCharacters() {
		// Twenty euro signs: 20 characters, 60 UTF-8 bytes. Measured with String.length() the
		// payload fit inside a 40-byte cap it exceeds by 28 bytes - and the option is named
		// maxOutputBytes because bytes are what the JSON column stores.
		ScriptNode node = configured(def("out.text('caption', '€'.repeat(20));", outputs("caption", "TEXT"))
			.put("maxOutputBytes", 40));

		NodeResult result = node.process(NodeContext.create(media, NodeInputs.empty()));

		assertEquals(ResultState.FAILED, result.getState(), result.getMessage());
		assertThat(result.getMessage()).contains("exceeds maxOutputBytes of 40");
	}

	@Test
	void shouldAcceptAnAsciiPayloadOfTheSameCharacterCount() {
		// The same twenty characters in ASCII are twenty bytes and must still pass, or the fix would
		// merely have moved the cap.
		ScriptNode node = configured(def("out.text('caption', 'x'.repeat(20));", outputs("caption", "TEXT"))
			.put("maxOutputBytes", 40));

		NodeResult result = node.process(NodeContext.create(media, NodeInputs.empty()));
		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
	}

	// ----------------------------------------------------------------------------------------
	// Images: the base64-vs-path discriminator, end to end
	// ----------------------------------------------------------------------------------------

	@Test
	void shouldWriteUnpaddedBase64ImageBytesInsteadOfEmittingThemAsAPath() {
		// The discriminator used to be `indexOf('/') < 0`, but '/' is a base64 character: any
		// base64 image of real size contains one. An unpadded blob therefore took the path branch,
		// and the emitted "path" was the base64 string itself.
		byte[] bytes = new byte[600];
		for (int i = 0; i < bytes.length; i++) {
			bytes[i] = (byte) (i % 4 == 0 ? 0xFF : i * 11);
		}
		String base64 = Base64.getEncoder().encodeToString(bytes);
		assertThat(base64).contains("/").doesNotEndWith("=");

		ScriptNode node = configured(def("out.image('frame', params.b64);", outputs("frame", "IMAGE"))
			.put("params", new JsonObject().put("b64", base64)));

		NodeResult result = node.process(NodeContext.create(media, NodeInputs.empty()));
		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());

		String emitted = (String) result.get(new ScriptOutputSpec("frame", ScriptValueType.IMAGE).port());
		Path file = Path.of(emitted);
		assertTrue(file.startsWith(tempDir.toPath().resolve(ScriptNode.BIN_DIR)),
			"the emitted value must be a path under metaPath/" + ScriptNode.BIN_DIR + ", got " + emitted);
		assertTrue(Files.exists(file), "expected the decoded bytes at " + emitted);
	}
}
