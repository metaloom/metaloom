package io.metaloom.loom.cortex.node.facedescription;

import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guard for the tests that need the local vision model backing {@code FacedescriptionNode}.
 *
 * <p>
 * {@code FacedescriptionNode#processFace} asks an OpenAI-compatible vision backend for a JSON
 * description and returns {@code null} after three failed attempts, so without an endpoint the test
 * failed on a {@code NullPointerException} — which reads exactly like a real regression rather than
 * a missing prerequisite. Same pattern as {@code SmolVLMAvailability} in the captioning node and
 * {@code TestEnv} in the llm node.
 * </p>
 */
public final class VisionBackendAvailability {

	private static final Logger log = LoggerFactory.getLogger(VisionBackendAvailability.class);

	public static final String HOST = System.getProperty("loom.test.vision.host", "127.0.0.1");

	/** The port llama.cpp's {@code llama-server} serves the OpenAI-compatible API on. */
	public static final int PORT = Integer.getInteger("loom.test.vision.port", 8080);

	private static final int CONNECT_TIMEOUT_MS = 500;

	private VisionBackendAvailability() {
	}

	/**
	 * Skip the calling test when nothing is listening on the vision backend port.
	 */
	public static void assumeRunning() {
		Assumptions.assumeTrue(isRunning(), "Skipping - no vision endpoint reachable at " + HOST + ":" + PORT);
	}

	public static boolean isRunning() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
			return true;
		} catch (Exception e) {
			log.debug("No vision endpoint reachable at {}:{}", HOST, PORT, e);
			return false;
		}
	}
}
