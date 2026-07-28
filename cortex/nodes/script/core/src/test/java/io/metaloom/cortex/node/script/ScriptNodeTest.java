package io.metaloom.cortex.node.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.media.LoomMedia;
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
 * Unit test for {@link ScriptNode} driving the <strong>real</strong> GraalJS engine.
 *
 * <p>
 * Deliberately not stubbed: the engine is the node's whole behaviour, and the properties that
 * matter (sandboxing, the statement limit, the timeout) only hold if the real context builder is
 * exercised. There is no Loom client, so persistence is a no-op here - that is
 * {@code ScriptNodePersistenceTest}'s job.
 * </p>
 */
class ScriptNodeTest {

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

	private ScriptNode node() {
		Map<String, Provider<ScriptEngine>> engines = Map.of(GraalJsScriptEngine.ID, GraalJsScriptEngine::new);
		return new ScriptNode(null, cortexOptions, new ScriptNodeOptions(), engines);
	}

	/** Configure a node from a definition, the same shape NodeTaskRunner flattens onto a task. */
	private ScriptNode configured(JsonObject def) {
		ScriptNode node = node();
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

	private static JsonArray outputs(String... keyTypePairs) {
		JsonArray array = new JsonArray();
		for (int i = 0; i < keyTypePairs.length; i += 2) {
			array.add(new JsonObject().put("key", keyTypePairs[i]).put("type", keyTypePairs[i + 1]));
		}
		return array;
	}

	private NodeResult run(ScriptNode node) {
		return run(node, Map.of());
	}

	private NodeResult run(ScriptNode node, Map<String, Map<String, Object>> upstream) {
		NodeContext<LoomMedia> ctx = NodeContext.create(media, upstream);
		return node.process(ctx);
	}

	@Test
	void shouldEmitDeclaredScalarOutputs() {
		ScriptNode node = configured(def("""
			out.text('caption', 'a red car');
			out.integer('count', 3);
			out.number('score', 0.5);
			out.bool('ok', true);
			out.json('meta', { a: 1, b: 'two' });
			""", outputs("caption", "TEXT", "count", "INTEGER", "score", "NUMBER", "ok", "BOOLEAN", "meta", "JSON")));

		NodeResult result = run(node);

		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
		assertEquals("a red car", result.getOutputs().get("caption"));
		assertEquals(3L, result.getOutputs().get("count"));
		assertEquals(0.5d, result.getOutputs().get("score"));
		assertEquals(Boolean.TRUE, result.getOutputs().get("ok"));
		assertEquals(new JsonObject().put("a", 1L).put("b", "two"), result.getOutputs().get("meta"));
	}

	/**
	 * The headline capability: one text in, several multi-valued outputs out. This is what "a
	 * stream of timeframes or multiple texts" means without the pipeline engine multiplying items.
	 */
	@Test
	@SuppressWarnings("unchecked")
	void shouldEmitMultiValuedOutputsFromASingleUpstreamText() {
		ScriptNode node = configured(def("""
			const transcript = JSON.parse(upstream['whisper']['whisper_result']);
			const frames = transcript.segments
			  .filter(s => /chapter/i.test(s.text))
			  .map(s => ({ startMs: Math.round(s.start * 1000), endMs: Math.round(s.end * 1000), label: s.text }));
			out.timeframes('chapter_frames', frames);
			out.list('chapter_titles', frames.map(f => f.label));
			out.integer('chapter_count', frames.length);
			""", outputs("chapter_frames", "TIMEFRAMES", "chapter_titles", "TEXT_LIST", "chapter_count", "INTEGER")));

		String transcript = new JsonObject().put("segments", new JsonArray()
			.add(new JsonObject().put("start", 0.0).put("text", "welcome").put("end", 1.0))
			.add(new JsonObject().put("start", 2.0).put("text", "Chapter one").put("end", 3.5))
			.add(new JsonObject().put("start", 9.0).put("text", "Chapter two").put("end", 10.25)))
			.encode();

		NodeResult result = run(node, Map.of("whisper", Map.of("whisper_result", transcript)));

		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
		assertEquals(2L, result.getOutputs().get("chapter_count"));

		List<String> titles = (List<String>) result.getOutputs().get("chapter_titles");
		assertEquals(List.of("Chapter one", "Chapter two"), titles);

		List<JsonObject> frames = (List<JsonObject>) result.getOutputs().get("chapter_frames");
		assertEquals(2, frames.size());
		assertEquals(2000L, frames.get(0).getLong("startMs"));
		assertEquals(3500L, frames.get(0).getLong("endMs"));
		assertEquals("Chapter one", frames.get(0).getString("label"));
		assertEquals(10250L, frames.get(1).getLong("endMs"));
	}

	@Test
	void shouldReadTheMediaFacadeAndParams() {
		ScriptNode node = configured(def("""
			out.text('summary', media.isVideo + '|' + params.tag + '|' + (media.sha512.length));
			""", outputs("summary", "TEXT"))
			.put("params", new JsonObject().put("tag", "demo")));

		NodeResult result = run(node);

		assertEquals("true|demo|128", result.getOutputs().get("summary"));
	}

	@Test
	void shouldFailOnAnUndeclaredOutputKey() {
		ScriptNode node = configured(def("out.text('typo_key', 'x');", outputs("caption", "TEXT")));

		NodeResult result = run(node);

		assertEquals(ResultState.FAILED, result.getState());
		assertTrue(result.getMessage().contains("typo_key"), result.getMessage());
		assertTrue(result.getMessage().contains("caption"), "the message should list the declared keys: " + result.getMessage());
	}

	@Test
	void shouldFailWhenAValueCannotBeCoerced() {
		ScriptNode node = configured(def("out.integer('count', 'not a number');", outputs("count", "INTEGER")));

		NodeResult result = run(node);

		assertEquals(ResultState.FAILED, result.getState());
		assertTrue(result.getMessage().contains("count"), result.getMessage());
	}

	@Test
	void shouldMapCtxSkipToSkipped() {
		ScriptNode node = configured(def("ctx.skip('nothing to do'); out.text('caption', 'never');", outputs("caption", "TEXT")));

		NodeResult result = run(node);

		assertEquals(ResultState.SKIPPED, result.getState());
		assertEquals("nothing to do", result.getMessage());
		assertFalse(result.getOutputs().containsKey("caption"), "a skipped script must not emit");
	}

	@Test
	void shouldMapCtxFailToFailed() {
		ScriptNode node = configured(def("ctx.fail('bad input');", outputs("caption", "TEXT")));

		NodeResult result = run(node);

		assertEquals(ResultState.FAILED, result.getState());
		assertEquals("bad input", result.getMessage());
	}

	@Test
	void shouldReportAGuestSyntaxErrorAtConfigureTime() {
		// Compiling per node rather than per media item means an author sees this once, immediately.
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> configured(def("this is not javascript(", outputs("caption", "TEXT"))));
		assertTrue(e.getMessage().contains("did not compile"), e.getMessage());
	}

	@Test
	void shouldStopAnInfiniteLoopWithinTheTimeout() {
		ScriptNode node = configured(def("while (true) { }", outputs("caption", "TEXT"))
			.put("timeoutMs", 1500L)
			// Raised above the loop's reach so the wall clock is what stops it, not the counter.
			.put("statementLimit", Long.MAX_VALUE));

		long start = System.currentTimeMillis();
		NodeResult result = run(node);
		long elapsed = System.currentTimeMillis() - start;

		assertEquals(ResultState.FAILED, result.getState());
		assertTrue(result.getMessage().contains("time budget"), result.getMessage());
		assertTrue(elapsed < 15_000, "the watchdog should have stopped the script promptly, took " + elapsed + "ms");
	}

	@Test
	void shouldStopAnInfiniteLoopWithTheStatementLimit() {
		ScriptNode node = configured(def("while (true) { }", outputs("caption", "TEXT"))
			.put("timeoutMs", 60_000L)
			.put("statementLimit", 50_000L));

		NodeResult result = run(node);

		assertEquals(ResultState.FAILED, result.getState());
		assertTrue(result.getMessage().contains("statement budget"), result.getMessage());
	}

	@Test
	void shouldDenyHostClassLookupWhenNotTrusted() {
		ScriptNode node = configured(def("""
			const System = Java.type('java.lang.System');
			out.text('leak', String(System.getenv('PATH')));
			""", outputs("leak", "TEXT")).put("trusted", false));

		NodeResult result = run(node);

		assertEquals(ResultState.FAILED, result.getState());
		assertTrue(result.getMessage().contains("java.lang.System") || result.getMessage().contains("Java"),
			"a sandboxed script must not reach host classes: " + result.getMessage());
	}

	@Test
	void shouldAllowHostAccessWhenTrusted() {
		// The mirror of the previous test: the sandbox flag is what makes the difference, so the
		// trusted path must demonstrably still work.
		ScriptNode node = configured(def("""
			const System = Java.type('java.lang.System');
			out.integer('answer', 42);
			""", outputs("answer", "INTEGER")).put("trusted", true));

		NodeResult result = run(node);

		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
		assertEquals(42L, result.getOutputs().get("answer"));
	}

	@Test
	void shouldSkipWhenARequiredInputIsMissing() {
		ScriptNode node = configured(def("out.text('caption', upstream['ocr']['ocr_text']);", outputs("caption", "TEXT"))
			.put("requiredInputs", new JsonArray().add("ocr:ocr_text")));

		NodeResult result = run(node);

		assertEquals(ResultState.SKIPPED, result.getState());
	}

	@Test
	void shouldRunWhenARequiredInputIsPresent() {
		ScriptNode node = configured(def("out.text('caption', upstream['ocr']['ocr_text']);", outputs("caption", "TEXT"))
			.put("requiredInputs", new JsonArray().add("ocr:ocr_text")));

		NodeResult result = run(node, Map.of("ocr", Map.of("ocr_text", "STOP")));

		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
		assertEquals("STOP", result.getOutputs().get("caption"));
	}

	@Test
	void shouldFailWhenTheOutputBagExceedsTheCap() {
		ScriptNode node = configured(def("out.text('blob', 'x'.repeat(5000));", outputs("blob", "TEXT"))
			.put("maxOutputBytes", 1024));

		NodeResult result = run(node);

		assertEquals(ResultState.FAILED, result.getState());
		assertTrue(result.getMessage().contains("maxOutputBytes"), result.getMessage());
	}

	/**
	 * {@code NodeResult} carries no origin accessor, so the cache is observed rather than asserted
	 * on: the script emits a fresh nanoTime each execution, so an unchanged value proves the second
	 * run never reached the engine.
	 */
	private static final String NONDETERMINISTIC = "out.text('caption', String(Java.type('java.lang.System').nanoTime()));";

	@Test
	void shouldServeARepeatFromTheLocalCache() {
		ScriptNode node = configured(def(NONDETERMINISTIC, outputs("caption", "TEXT")));

		Object first = run(node).getOutputs().get("caption");
		Object second = run(node).getOutputs().get("caption");

		assertNotNull(first);
		assertEquals(first, second, "the second run should have been served from the local cache");
	}

	/**
	 * The cache key includes the script hash. Without that, editing a script would re-emit the
	 * previous script's results for the worker's lifetime with no way to invalidate them.
	 */
	@Test
	void shouldNotServeACachedResultAfterTheScriptChanges() {
		ScriptNode node = node();
		node.configure(def("out.text('caption', 'first');", outputs("caption", "TEXT")));
		assertEquals("first", run(node).getOutputs().get("caption"));

		node.configure(def("out.text('caption', 'second');", outputs("caption", "TEXT")));

		assertEquals("second", run(node).getOutputs().get("caption"),
			"a changed script must not be served the previous script's cached result");
	}

	@Test
	void shouldWriteImageOutputsToTheLocalBinCache() {
		// A 1x1 PNG, base64 - the form a script most naturally produces.
		String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
		ScriptNode node = configured(def("out.image('frames', [params.png, params.png]);", outputs("frames", "IMAGE_LIST"))
			.put("params", new JsonObject().put("png", png)));

		NodeResult result = run(node);

		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
		@SuppressWarnings("unchecked")
		List<String> paths = (List<String>) result.getOutputs().get("frames");
		assertEquals(2, paths.size());
		for (String path : paths) {
			Path file = Path.of(path);
			assertTrue(Files.exists(file), "expected the image at " + path);
			assertTrue(file.startsWith(tempDir.toPath().resolve(ScriptNode.BIN_DIR)),
				"images belong under metaPath/" + ScriptNode.BIN_DIR + ", got " + path);
		}
	}

	@Test
	void shouldRecordTheScriptDigestAsTheProducerVersion() {
		ScriptNode a = configured(def("out.text('caption', 'one');", outputs("caption", "TEXT")));
		ScriptNode b = configured(def("out.text('caption', 'two');", outputs("caption", "TEXT")));

		assertTrue(a.producerVersion().startsWith("js:"), a.producerVersion());
		assertNotEquals(a.producerVersion(), b.producerVersion());
	}

	/**
	 * The script seeded by {@code DemoDatabaseInitializer} ("Reading Time"). Demo data that does not
	 * actually run is worse than none - the first thing a new user opens would be broken - so the
	 * seeded script is exercised here. Keep the two in step.
	 */
	@Test
	void shouldRunTheDemoReadingTimeScript() {
		String demoScript = """
			// Estimate reading time from the text Tika extracted upstream.
			const text = upstream['pn2']['tika_content'] || '';
			const words = text.split(/\\s+/).filter(w => w.length > 0).length;
			const minutes = Math.max(1, Math.round(words / params.wordsPerMinute));

			out.integer('reading_minutes', minutes);
			out.string('length_band', minutes <= 2 ? 'short' : minutes <= 10 ? 'medium' : 'long');
			log.info(words + ' words, about ' + minutes + ' minute(s)');
			""";

		ScriptNode node = configured(def(demoScript, outputs("reading_minutes", "INTEGER", "length_band", "STRING"))
			.put("params", new JsonObject().put("wordsPerMinute", 200))
			.put("requiredInputs", new JsonArray().add("pn2:tika_content")));

		String text = "word ".repeat(1000);
		NodeResult result = run(node, Map.of("pn2", Map.of("tika_content", text)));

		assertEquals(ResultState.SUCCESS, result.getState(), result.getMessage());
		assertEquals(5L, result.getOutputs().get("reading_minutes"), "1000 words at 200 wpm");
		assertEquals("medium", result.getOutputs().get("length_band"));
	}

	@Test
	void shouldRejectAnUnknownEngine() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
			() -> configured(def("out.text('caption', 'x');", outputs("caption", "TEXT")).put("engine", "brainfuck")));
		assertTrue(e.getMessage().contains("brainfuck"), e.getMessage());
	}

	@Test
	void shouldSkipWhenNotConfigured() {
		// An unconfigured script node has nothing to run; saying so beats reporting success.
		NodeResult result = run(node());
		assertEquals(ResultState.SKIPPED, result.getState());
	}

	/**
	 * {@code configure} mutates the node, so the runtime must hand out a fresh instance per task.
	 * Marking {@link ScriptNode} {@code @Singleton} would let two concurrent script nodes overwrite
	 * each other's script; this pins the provider contract that prevents it.
	 */
	@Test
	void shouldGiveEachProviderCallItsOwnInstance() {
		Provider<ScriptNode> provider = this::node;
		ScriptNode first = provider.get();
		ScriptNode second = provider.get();
		assertNotSame(first, second);

		first.configure(def("out.text('caption', 'first');", outputs("caption", "TEXT")));
		second.configure(def("out.text('caption', 'second');", outputs("caption", "TEXT")));

		assertEquals("first", run(first).getOutputs().get("caption"));
		assertEquals("second", run(second).getOutputs().get("caption"));
		assertNull(ScriptNode.class.getAnnotation(javax.inject.Singleton.class),
			"ScriptNode must not be @Singleton - configure() mutates it per task");
	}

	private static void assertNotEquals(String unexpected, String actual) {
		assertFalse(unexpected.equals(actual), "expected a different value than " + unexpected);
	}
}
