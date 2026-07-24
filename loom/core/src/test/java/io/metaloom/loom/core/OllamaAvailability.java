package io.metaloom.loom.core;

import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guard for the tests that need a local Ollama instance.
 *
 * <p>
 * Those tests document an external prerequisite ("a running Ollama instance at http://127.0.0.1:11434 with the gpt-oss:20b model"). Without it they
 * used to fail the build with a bare {@code ConnectException}, which is indistinguishable from a real regression. Assuming the prerequisite instead
 * skips them on machines that do not have it, and still runs them where it is present.
 * </p>
 */
public final class OllamaAvailability {

	private static final Logger log = LoggerFactory.getLogger(OllamaAvailability.class);

	public static final String HOST = "127.0.0.1";

	public static final int PORT = 11434;

	private static final int CONNECT_TIMEOUT_MS = 500;

	private OllamaAvailability() {
	}

	/**
	 * Skip the calling test when nothing is listening on the Ollama port.
	 */
	public static void assumeRunning() {
		Assumptions.assumeTrue(isRunning(), "Skipping - no Ollama instance reachable at " + HOST + ":" + PORT);
	}

	public static boolean isRunning() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
			return true;
		} catch (Exception e) {
			log.debug("No Ollama instance reachable at {}:{}", HOST, PORT, e);
			return false;
		}
	}
}
