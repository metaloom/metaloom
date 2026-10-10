package io.metaloom.cortex.node.captioning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * Exercises {@link SmolVLMClient} against an in-process HTTP server, so the status handling is
 * covered without a SmolVLM sidecar.
 */
class SmolVLMClientHttpTest {

	private HttpServer server;

	private SmolVLMClient start(int status, String body) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/caption", exchange -> {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(bytes);
			}
		});
		server.start();
		return new SmolVLMClient("127.0.0.1", server.getAddress().getPort());
	}

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void testSuccessReturnsBody() throws Exception {
		SmolVLMClient client = start(200, "A cat on a sofa.");
		assertEquals("A cat on a sofa.", client.captionByImageData("aGVsbG8="));
	}

	@Test
	void testServerErrorIsNotReturnedAsCaption() throws Exception {
		// A non-2xx body used to be handed back as the caption, then cached and persisted as SUCCESS.
		SmolVLMClient client = start(500, "Internal Server Error");
		RuntimeException e = assertThrows(RuntimeException.class, () -> client.captionByImageData("aGVsbG8="));
		assertTrue(e.getMessage().contains("HTTP 500"), e.getMessage());
	}
}
