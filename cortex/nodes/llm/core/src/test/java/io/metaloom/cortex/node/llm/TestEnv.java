package io.metaloom.cortex.node.llm;

import java.net.InetSocketAddress;
import java.net.Socket;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Endpoint and guard for the tests that need a local LLM.
 *
 * <p>
 * The backing server is the llama.cpp container from {@code loom-test-env/llamacpp/start.sh}. It
 * speaks the OpenAI protocol, so the node talks to it through {@code LLMProviderType.VLLM} rather
 * than the Ollama provider — the endpoint just has to be OpenAI-compatible, and llama.cpp is the
 * cheapest one to stand up for a test.
 * </p>
 *
 * <p>
 * Without a server these tests used to fail the build with a bare connection error, which reads
 * exactly like a real regression. Assuming the prerequisite skips them where it is missing and
 * still runs them where it is present.
 * </p>
 */
public final class TestEnv {

	private static final Logger log = LoggerFactory.getLogger(TestEnv.class);

	public static final String HOST = System.getProperty("loom.test.llm.host", "127.0.0.1");

	/** Not 11434 (Ollama) and not 8888 — see the port note in llamacpp/start.sh. */
	public static final int PORT = Integer.getInteger("loom.test.llm.port", 8899);

	/** OpenAI API root. The OpenAI client appends {@code chat/completions}, so {@code /v1} belongs here. */
	public static final String LLM_URL = "http://" + HOST + ":" + PORT + "/v1";

	private static final int CONNECT_TIMEOUT_MS = 500;

	private TestEnv() {
	}

	/**
	 * Skip the calling test when nothing is listening on the test LLM port.
	 */
	public static void assumeRunning() {
		Assumptions.assumeTrue(isRunning(),
			"Skipping - no LLM server reachable at " + HOST + ":" + PORT + " (start one with loom-test-env/llamacpp/start.sh)");
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
