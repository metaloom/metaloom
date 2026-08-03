package io.metaloom.loom.core;

import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guard for the tests that need a local OpenAI-compatible LLM server.
 *
 * <p>
 * Those tests document an external prerequisite ("a running LLM server at
 * http://127.0.0.1:8080/v1 serving a tool-calling model"). Without it they used to fail the build
 * with a bare connection error, which reads exactly like a real regression. Assuming the
 * prerequisite skips them where it is missing and still runs them where it is present.
 * </p>
 */
public final class LlmBackendAvailability {

	private static final Logger log = LoggerFactory.getLogger(LlmBackendAvailability.class);

	public static final String HOST = "127.0.0.1";

	/** The port llama.cpp's {@code llama-server} serves the OpenAI-compatible API on. */
	public static final int PORT = 8080;

	public static final String URL = "http://" + HOST + ":" + PORT + "/v1";

	private static final int CONNECT_TIMEOUT_MS = 500;

	private LlmBackendAvailability() {
	}

	/**
	 * Skip the calling test when nothing is listening on the LLM server port.
	 */
	public static void assumeRunning() {
		Assumptions.assumeTrue(isRunning(), "Skipping - no LLM server reachable at " + HOST + ":" + PORT);
	}

	public static boolean isRunning() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
			return true;
		} catch (Exception e) {
			log.debug("No LLM server reachable at {}:{}", HOST, PORT, e);
			return false;
		}
	}
}
