package io.metaloom.cortex.node.tts;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Exercises {@link TtsClient} against an in-process HTTP server, so the transport is covered
 * without a TTS sidecar.
 *
 * <p>
 * The client used to build a fresh {@link HttpClient} for every {@code synthesize} call and never
 * closed it, so each item left a selector thread and a connection pool behind for the life of the
 * worker. One client per {@link TtsClient} instance is the contract pinned here.
 * </p>
 */
class TtsClientHttpTest {

	private static final byte[] FAKE_WAV = "RIFF....WAVEfake-audio".getBytes(StandardCharsets.UTF_8);

	private HttpServer server;

	private TtsClient start(int status, byte[] body, AtomicInteger requests) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/tts", exchange -> {
			requests.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(status, body.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(body);
			}
		});
		server.start();
		return new TtsClient("127.0.0.1", server.getAddress().getPort());
	}

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void testSequentialCallsShareOneHttpClient() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		TtsClient client = start(200, FAKE_WAV, requests);

		HttpClient before = client.httpClient();
		assertArrayEquals(FAKE_WAV, client.synthesize("Hallo Welt", "de", "Jakob"));
		HttpClient afterFirst = client.httpClient();
		assertArrayEquals(FAKE_WAV, client.synthesize("Hello world", "en", "af_heart"));
		HttpClient afterSecond = client.httpClient();

		assertEquals(2, requests.get(), "Both calls must reach the sidecar");
		assertSame(before, afterFirst, "The first call must not replace the client");
		assertSame(afterFirst, afterSecond, "The second call must reuse the client the first one used");
		assertEquals(HttpClient.Version.HTTP_1_1, afterSecond.version(), "FastAPI rejects the JDK client's HTTP/2 upgrade");
	}

	@Test
	void testServerErrorIsNotReturnedAsAudio() throws Exception {
		TtsClient client = start(500, "Internal Server Error".getBytes(StandardCharsets.UTF_8), new AtomicInteger());
		RuntimeException e = assertThrows(RuntimeException.class, () -> client.synthesize("Hallo Welt", "de", "Jakob"));
		assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
	}
}
