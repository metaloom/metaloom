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

import io.metaloom.loom.api.Loom;
import io.metaloom.loom.api.options.AuthenticationOptions;
import io.metaloom.loom.api.options.DatabaseOptions;
import io.metaloom.loom.api.options.LoomOptions;
import io.metaloom.loom.api.options.LoomOptionsLookup;
import io.metaloom.loom.api.options.ServerOptions;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;

/**
 * Full end-to-end test that:
 * <ol>
 *   <li>Connects to a fresh PostgreSQL database</li>
 *   <li>Starts a real Loom backend (Flyway migrations create the schema)</li>
 *   <li>Starts the loom-ui Vite dev server with VITE_API_BASE_URL pointing at the backend</li>
 *   <li>Runs Playwright against the loom-ui login page</li>
 *   <li>Verifies login works end-to-end through the real REST API</li>
 * </ol>
 */
public class LoginE2ETest {

	private static final Logger log = LoggerFactory.getLogger(LoginE2ETest.class);

	private static final String DB_HOST = System.getProperty("db.host", "localhost");
	private static final int DB_PORT = Integer.getInteger("db.port", 5432);
	private static final String DB_NAME = System.getProperty("db.name", "loom_e2e");
	private static final String DB_USER = System.getProperty("db.user", "loom");
	private static final String DB_PASSWORD = System.getProperty("db.password", "loom");
	private static final int REST_PORT = Integer.getInteger("rest.port", 0);

	private static Loom server;
	private static int restPort;

	@BeforeAll
	static void startServer() throws Exception {
		LoomOptions options = new LoomOptions();

		// Database – fresh PostgreSQL with Flyway migrations on startup
		DatabaseOptions dbOptions = new DatabaseOptions();
		dbOptions.setHost(DB_HOST);
		dbOptions.setPort(DB_PORT);
		dbOptions.setUsername(DB_USER);
		dbOptions.setPassword(DB_PASSWORD);
		dbOptions.setDatabaseName(DB_NAME);
		options.setDatabase(dbOptions);
		log.info("Using PostgreSQL at {}:{}/{}", DB_HOST, DB_PORT, DB_NAME);

		// Server – use configured port or 0 for OS-assigned free port
		ServerOptions serverOptions = options.getServer();
		serverOptions.setBindAddress("localhost");
		serverOptions.setRestPort(REST_PORT);
		serverOptions.setGrpcPort(0);

		// Auth
		AuthenticationOptions authOptions = options.getAuth();
		authOptions.setKeystorePassword("finger");

		File baseFolder = new File("target", "e2e-test-config");
		File keystoreFile = new File(baseFolder, "keystore.jceks");
		if (keystoreFile.exists()) {
			keystoreFile.delete();
		}

		LoomOptionsLookup lookup = new LoomOptionsLookup(baseFolder, options);
		server = Loom.create(lookup);
		server.run(false);
		restPort = server.actualRestPort();
		log.info("Loom backend started on port {}", restPort);
	}

	@AfterAll
	static void stopServer() {
		if (server != null) {
			server.shutdown();
		}
	}

	/**
	 * Sanity check: verify the REST client can log in directly (no UI involved).
	 */
	@Test
	void testRestLoginDirectly() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(restPort)
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
		String proxyTarget = "http://localhost:" + restPort;
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

	/**
	 * Resolve the loom-ui directory. The module lives at {@code metaloom/loom-ui-e2e},
	 * and loom-ui is its sibling at {@code metaloom/loom-ui}.
	 */
	private static File resolveLoomUiDir() {
		// 1. Env var override
		String envDir = System.getenv("LOOM_UI_DIR");
		if (envDir != null) {
			File f = new File(envDir);
			if (isLoomUiDir(f)) {
				return f;
			}
		}

		// 2. Sibling directory (both modules are under metaloom/)
		File[] candidates = {
			new File("../loom-ui"),                // typical Maven CWD = module root
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
