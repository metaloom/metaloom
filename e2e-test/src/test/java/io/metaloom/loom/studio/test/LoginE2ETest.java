package io.metaloom.loom.studio.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;

/**
 * End-to-end test that starts the Loom demo jar as a local process
 * (backed by a host-local PostgreSQL instance) and verifies login
 * works through the real REST API.
 *
 * <p>Database options are configured via environment variables or
 * system properties (LOOM_DB_HOST, LOOM_DB_PORT, etc.).</p>
 */
public class LoginE2ETest {

private static final Logger log = LoggerFactory.getLogger(LoginE2ETest.class);

private static final int REST_PORT = 8092;
private static final String DB_HOST = System.getProperty("loom.db.host", "127.0.0.1");
private static final int DB_PORT = Integer.getInteger("loom.db.port", 5432);
private static final String DB_USER = System.getProperty("loom.db.username", "loom");
private static final String DB_PASS = System.getProperty("loom.db.password", "loom");
private static final String DB_NAME = System.getProperty("loom.db.name", "loom");

private static Process loomProcess;

@BeforeAll
static void startLoom() throws Exception {
String jarPath = System.getProperty("loom.jar", resolveLoomJar());
log.info("Starting Loom demo from jar: {}", jarPath);

ProcessBuilder pb = new ProcessBuilder(
"java",
"-Djna.tmpdir=/tmp/.jna",
"-Xms256m", "-Xmx512m",
"-jar", jarPath
);
pb.environment().put("LOOM_DB_HOST", DB_HOST);
pb.environment().put("LOOM_DB_PORT", String.valueOf(DB_PORT));
pb.environment().put("LOOM_DB_USERNAME", DB_USER);
pb.environment().put("LOOM_DB_PASSWORD", DB_PASS);
pb.environment().put("LOOM_DB_NAME", DB_NAME);
		pb.environment().put("LOOM_INITIAL_PASSWORD", "finger");

loomProcess = pb.start();

// Log output in background thread
Thread logThread = new Thread(() -> {
try (BufferedReader reader = new BufferedReader(new InputStreamReader(loomProcess.getInputStream()))) {
String line;
while ((line = reader.readLine()) != null) {
log.info("[loom] {}", line);
}
} catch (Exception e) {
log.debug("Loom log reader stopped", e);
}
}, "loom-log");
logThread.setDaemon(true);
logThread.start();

// Wait for the REST API to become available
waitForRestApi(Duration.ofSeconds(120));
log.info("Loom demo started, REST API at localhost:{}", REST_PORT);
}

@AfterAll
static void stopLoom() {
if (loomProcess != null && loomProcess.isAlive()) {
loomProcess.destroy();
try {
loomProcess.waitFor(10, TimeUnit.SECONDS);
} catch (InterruptedException e) {
Thread.currentThread().interrupt();
}
if (loomProcess.isAlive()) {
loomProcess.destroyForcibly();
}
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
.setPort(REST_PORT)
.build()) {

AuthLoginResponse response = client.login("admin", "finger").sync();
assertNotNull(response.getToken(), "Token should not be null after login");
}
}

/**
 * Full E2E: run Playwright from the loom-ui directory.
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
String proxyTarget = "http://localhost:" + REST_PORT;
int vitePort = findFreePort();
log.info("Running Playwright e2e tests against backend at {} (Vite on port {}, proxy to {})", apiBaseUrl, vitePort, proxyTarget);

ProcessBuilder ppb = new ProcessBuilder(
"npx", "playwright", "test", "e2e/login-backend.spec.ts", "--reporter=list"
);
ppb.directory(loomUiDir);
ppb.environment().put("VITE_API_BASE_URL", apiBaseUrl);
ppb.environment().put("VITE_PROXY_TARGET", proxyTarget);
ppb.environment().put("VITE_PORT", String.valueOf(vitePort));
ppb.redirectErrorStream(true);

Process proc = ppb.start();
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

private static void waitForRestApi(Duration timeout) throws Exception {
long deadline = System.currentTimeMillis() + timeout.toMillis();
while (System.currentTimeMillis() < deadline) {
try {
HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + REST_PORT + "/api/v1").openConnection();
conn.setConnectTimeout(2000);
conn.setReadTimeout(2000);
int code = conn.getResponseCode();
if (code > 0) {
log.info("REST API responded with status {}", code);
return;
}
} catch (Exception e) {
// Not ready yet
}
if (!loomProcess.isAlive()) {
throw new IllegalStateException("Loom process exited with code " + loomProcess.exitValue());
}
Thread.sleep(1000);
}
throw new IllegalStateException("Loom REST API did not become available within " + timeout);
}

private static String resolveLoomJar() {
String[] candidates = {
"../loom/containers/demo/target/loom-demo.jar",
System.getProperty("user.dir") + "/../loom/containers/demo/target/loom-demo.jar",
};
for (String path : candidates) {
File f = new File(path);
if (f.isFile()) {
return f.getAbsolutePath();
}
}
throw new IllegalStateException("Cannot find loom-demo.jar. Set -Dloom.jar=<path> or build the project first.");
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
