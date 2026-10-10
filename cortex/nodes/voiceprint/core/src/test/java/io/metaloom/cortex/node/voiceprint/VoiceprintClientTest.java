package io.metaloom.cortex.node.voiceprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import io.vertx.core.json.JsonObject;

/**
 * The wire format of {@link VoiceprintClient} against a local HTTP server: an HTTP/1.1 multipart POST to {@code /v1/voiceprints} carrying the
 * set fields only and the file bytes intact, and the sidecar's snake_case answer parsed into {@link VoiceprintResult}.
 */
class VoiceprintClientTest {

	@TempDir
	File tempDir;

	private HttpServer server;
	private final AtomicReference<String> contentType = new AtomicReference<>();
	private final AtomicReference<byte[]> body = new AtomicReference<>();
	private final AtomicReference<String> protocol = new AtomicReference<>();
	private volatile int status = 200;
	private volatile String answer = VoiceprintFixtures.sidecarJson().encode();

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/voiceprints", exchange -> {
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			protocol.set(exchange.getProtocol());
			body.set(exchange.getRequestBody().readAllBytes());
			byte[] out = answer.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, out.length);
			exchange.getResponseBody().write(out);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private VoiceprintClient client() {
		return new VoiceprintClient("127.0.0.1", server.getAddress().getPort());
	}

	@Test
	void testMultipartRequestAndParsedAnswer() throws Exception {
		File wav = VoiceprintFixtures.toneWav(tempDir, "talk.wav");
		VoiceprintResult result = client().analyze(wav.toPath(),
			new VoiceprintClient.Params("wespeaker", 3.0, 1.5, null, 0.4, null), 10_000);

		assertEquals("HTTP/1.1", protocol.get(), "FastAPI rejects the JDK client's HTTP/2 upgrade");
		assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
		String text = new String(body.get(), StandardCharsets.ISO_8859_1);
		assertTrue(text.contains("name=\"model\"\r\n\r\nwespeaker\r\n"));
		assertTrue(text.contains("name=\"window_s\"\r\n\r\n3.0\r\n"));
		assertTrue(text.contains("name=\"cluster_threshold\"\r\n\r\n0.4\r\n"));
		assertTrue(!text.contains("min_speech_s") && !text.contains("max_speakers"), "unset fields are left to the sidecar");
		assertTrue(text.contains("name=\"file\"; filename=\"talk.wav\""));
		byte[] wavBytes = Files.readAllBytes(wav.toPath());
		String wavText = new String(wavBytes, StandardCharsets.ISO_8859_1);
		assertTrue(text.contains(wavText), "the file bytes travel unchanged");
		assertTrue(text.endsWith("--\r\n"));

		assertEquals(VoiceprintFixtures.MODEL, result.model());
		assertEquals("redimnet2", result.backend());
		assertEquals(3, result.dim());
		assertEquals(12_000L, result.durationMs());
		assertEquals(2, result.speakers().size());
		assertEquals(1_000L, result.speakers().get(0).segments().get(0).startMs());
		assertEquals(4, result.windows().size());
		assertEquals(2_500L, result.windows().get(1).startMs());
		assertEquals(1, result.windows().get(2).speaker());
		assertEquals(0.6f, result.pooled()[0], 1e-6);
	}

	@Test
	void testSilenceHasNoPooledVector() throws Exception {
		answer = new JsonObject().put("model", "m").put("backend", "b").put("dim", 3).put("duration_ms", 1000).put("speech_ms", 0)
			.put("speakers", new io.vertx.core.json.JsonArray()).put("windows", new io.vertx.core.json.JsonArray()).encode();
		VoiceprintResult result = client().analyze(VoiceprintFixtures.toneWav(tempDir, "s.wav").toPath(), VoiceprintClient.Params.defaults(), 0);
		assertTrue(!result.hasSpeech());
		assertNull(result.pooled());
	}

	@Test
	void testHttpErrorCarriesTheSidecarMessage() throws Exception {
		status = 400;
		answer = "{\"detail\":\"unknown or disabled model 'nope'\"}";
		File wav = VoiceprintFixtures.toneWav(tempDir, "e.wav");
		RuntimeException e = assertThrows(RuntimeException.class, () -> client().analyze(wav.toPath(), VoiceprintClient.Params.defaults(), 0));
		assertTrue(e.getMessage().contains("HTTP 400") && e.getMessage().contains("nope"), e.getMessage());
	}

	@Test
	void testUnreachableSidecarFails() {
		VoiceprintClient dead = new VoiceprintClient("127.0.0.1", 1);
		File wav = new File(tempDir, "missing.wav");
		assertThrows(RuntimeException.class, () -> dead.analyze(wav.toPath(), VoiceprintClient.Params.defaults(), 1000));
	}
}
