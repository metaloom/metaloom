package io.metaloom.cortex.node.captioning;

import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guard for the tests that need a local SmolVLM endpoint.
 *
 * <p>
 * Without one those tests failed the build with a bare connection error, which is indistinguishable
 * from a real regression. Assuming the prerequisite skips them on machines that do not have it and
 * still runs them where it is present — the same pattern as {@code TestEnv} in
 * {@code loom/core} and {@code TestEnv} in the llm node.
 * </p>
 */
public final class SmolVLMAvailability {

	private static final Logger log = LoggerFactory.getLogger(SmolVLMAvailability.class);

	public static final String HOST = System.getProperty("loom.test.smolvlm.host", "localhost");

	public static final int PORT = Integer.getInteger("loom.test.smolvlm.port", 8000);

	private static final int CONNECT_TIMEOUT_MS = 500;

	private SmolVLMAvailability() {
	}

	/**
	 * Skip the calling test when nothing is listening on the SmolVLM port.
	 */
	public static void assumeRunning() {
		Assumptions.assumeTrue(isRunning(), "Skipping - no SmolVLM endpoint reachable at " + HOST + ":" + PORT);
	}

	public static boolean isRunning() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
			return true;
		} catch (Exception e) {
			log.debug("No SmolVLM endpoint reachable at {}:{}", HOST, PORT, e);
			return false;
		}
	}
}
