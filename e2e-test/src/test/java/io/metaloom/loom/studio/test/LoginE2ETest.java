package io.metaloom.loom.studio.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;

/**
 * Full end-to-end test that:
 * <ol>
 *   <li>Starts a PostgreSQL container</li>
 *   <li>Starts the Loom demo container (Flyway migrations create the schema)</li>
 *   <li>Optionally starts the loom-ui Vite dev server with Playwright</li>
 *   <li>Verifies login works end-to-end through the real REST API</li>
 * </ol>
 *
 * <p>The demo container image must be built locally before running this test.
 * See {@code loom/containers/build-containers.sh}.</p>
 */
public class LoginE2ETest {

	private static final Logger log = LoggerFactory.getLogger(LoginE2ETest.class);

	private static final String LOOM_IMAGE = System.getProperty("loom.image", "metaloom/loom-demo:latest");
	private static final int REST_PORT = 8092;

	private static Network network;
	private static PostgreSQLContainer<?> postgres;
	private static GenericContainer<?> loom;

	@BeforeAll
	static void startContainers() {
		network = Network.newNetwork();

		postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
			.withNetwork(network)
			.withNetworkAliases("postgres")
			.withDatabaseName("loom")
			.withUsername("loom")
			.withPassword("loom");
		postgres.start();
		log.info("PostgreSQL started at {}:{}", postgres.getHost(), postgres.getMappedPort(5432));

		loom = new GenericContainer<>(DockerImageName.parse(LOOM_IMAGE))
			.withNetwork(network)
			.withExposedPorts(REST_PORT)
			.withCopyFileToContainer(
				MountableFile.forClasspathResource("loom-e2e.yml"),
				"/config/loom.yml")
			.withLogConsumer(new Slf4jLogConsumer(log).withPrefix("loom"))
			.waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120)));
		loom.start();
		log.info("Loom demo container started, REST API at {}:{}", loom.getHost(), loom.getMappedPort(REST_PORT));
	}

	@AfterAll
	static void stopContainers() {
		if (loom != null) {
			loom.stop();
		}
		if (postgres != null) {
			postgres.stop();
		}
		if (network != null) {
			network.close();
		}
	}

	/**
	 * Sanity check: verify the REST client can log in directly (no UI involved).
	 */
	@Test
	void testRestLoginDirectly() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname(loom.getHost())
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(loom.getMappedPort(REST_PORT))
			.build()) {

			AuthLoginResponse response = client.login("admin", "finger").sync();
			assertNotNull(response.getToken(), "Token should not be null after login");
		}
	}

	/**
	 * Full E2E: run Playwright from the loom-ui directory.
	 * <p>
	 * Playwright's config auto-starts a Vite dev server via {@code webServer.command}.
	 * We pass {@code VITE_API_BASE_URL} so Vite injects the correct backend URL,
	 * and {@code VITE_PORT} so the dev server uses a free port (avoiding conflicts).
	 * </p>
	 */
	@Test
	void testLoginViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright test. "
				+ "Set LOOM_UI_DIR env var or ensure ../loom-ui exists relative to this module.");
			return;
		}
		log.info("Using loom-ui at {}", loomUiDir.getAbsolutePath());

		String apiBaseUrl = "/api/v1";
		String proxyTarget = "http://" + loom.getHost() + ":" + loom.getMappedPort(REST_PORT);
		int vitePort = findFreePort();
		log.info("Running Playwright e2e tests against backend at {} (Vite on port {}, proxy to {})", apiBaseUrl, vitePort, proxyTarget);

		ProcessBuilder pb = new ProcessBuilder(
			"npx", "playwright", "test", "e2e/login-backend.spec.ts", "--reporter=list"
		);
		pb.directory(loomUiDir);
		// These env vars propagate through Playwright → webServer → Vite
		pb.environment().put("VITE_API_BASE_URL", apiBaseUrl);
		pb.environment().put("VITE_PROXY_TARGET", proxyTarget);
		pb.environment().put("VITE_PORT", String.valueOf(vitePort));
		pb.redirectErrorStream(true);

		Process proc = pb.start();
		StringBuilder output = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append("\n");
				log.info("[playwright] {}", line);
			}
		}

		boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
		if (!finished) {
			proc.destroyForcibly();
			throw new AssertionError("Playwright timed out after 120s");
		}

		assertEquals(0, proc.exitValue(),
			"Playwright tests failed (exit code " + proc.exitValue() + "):\n" + output);
	}

	private static File resolveLoomUiDir() {
		String envDir = System.getenv("LOOM_UI_DIR");
		if (envDir != null) {
			File f = new File(envDir);
			if (isLoomUiDir(f)) {
				return f;
			}
		}

		File[] candidates = {
			new File("../loom-ui"),
			new File(System.getProperty("user.dir"), "../loom-ui"),
		};

		for (File candidate : candidates) {
			if (isLoomUiDir(candidate)) {
				return candidate.getAbsoluteFile();
			}
		}
		return null;
	}

	private static boolean isLoomUiDir(File dir) {
		return dir.isDirectory()
			&& new File(dir, "package.json").exists()
			&& new File(dir, "e2e").isDirectory();
	}

	private static int findFreePort() throws Exception {
		try (ServerSocket s = new ServerSocket(0)) {
			return s.getLocalPort();
		}
	}
}
