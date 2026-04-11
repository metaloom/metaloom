package io.metaloom.loom.studio.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.group.GroupCreateRequest;
import io.metaloom.loom.rest.model.group.GroupListResponse;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.group.GroupUpdateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolCreateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolListResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagListResponse;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;

/**
 * End-to-end test that starts the Loom demo jar as a local process (backed by a host-local PostgreSQL instance) and verifies login works through the real REST
 * API.
 *
 * <p>
 * Database options are configured via environment variables or system properties (LOOM_DB_HOST, LOOM_DB_PORT, etc.).
 * </p>
 */
public class LoginE2ETest {

	private static final Logger log = LoggerFactory.getLogger(LoginE2ETest.class);

	private static final int REST_PORT = 8092;
	private static final String DB_HOST = System.getProperty("loom.db.host", "127.0.0.1");
	private static final int DB_PORT = Integer.getInteger("loom.db.port", 5432);

	// Privileged PostgreSQL credentials used to (re)create the loom database
	private static final String PG_ADMIN_USER = System.getProperty("loom.pg.admin.user", "postgres");
	private static final String PG_ADMIN_PASS = System.getProperty("loom.pg.admin.password", "finger");

	// Application-level credentials used by the Loom server at runtime
	private static final String DB_USER = System.getProperty("loom.db.username", "loom");
	private static final String DB_PASS = System.getProperty("loom.db.password", "loom");
	private static final String DB_NAME = System.getProperty("loom.db.name", "loom");

	private static Process loomProcess;

	@BeforeAll
	static void startLoom() throws Exception {
		setupDatabase();

		String jarPath = System.getProperty("loom.jar", resolveLoomJar());
		log.info("Starting Loom demo from jar: {}", jarPath);

		ProcessBuilder pb = new ProcessBuilder(
			"java",
			"-Djna.tmpdir=/tmp/.jna",
			"-Xms256m", "-Xmx512m",
			"-jar", jarPath);
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

			AuthLoginResponse response = client.login("admin", "finger").sync().body();
			assertNotNull(response.getToken(), "Token should not be null after login");
		}
	}

	/**
	 * Verify that the asset list endpoint returns demo assets populated by DemoDatabaseInitializer.
	 */
	@Test
	void testListAssets() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			assertNotNull(loginResp.getToken());
			client.setToken(loginResp.getToken());

			AssetListResponse listResp = client.listAssets().sync().body();
			assertNotNull(listResp, "Asset list response should not be null");
			assertNotNull(listResp.getData(), "Asset list data should not be null");
			assertFalse(listResp.getData().isEmpty(), "Asset list should contain demo assets");
			log.info("Listed {} assets", listResp.getData().size());

			// Verify one of the demo assets has expected properties
			AssetResponse first = listResp.getData().get(0);
			assertNotNull(first.getUuid(), "Asset UUID should not be null");
			assertNotNull(first.getFile(), "Asset file info should not be null");
			assertNotNull(first.getFile().getFilename(), "Asset filename should not be null");
			assertTrue(first.getFile().getSize() > 0, "Asset file size should be > 0");
		}
	}

	/**
	 * Verify that a single asset can be loaded by UUID and contains full metadata.
	 */
	@Test
	void testLoadSingleAsset() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List assets and pick the first UUID
			AssetListResponse listResp = client.listAssets().sync().body();
			assertFalse(listResp.getData().isEmpty(), "Need at least one asset");
			AssetResponse listed = listResp.getData().get(0);

			// Load by UUID
			AssetResponse loaded = client.loadAsset(listed.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded asset should not be null");
			assertEquals(listed.getUuid(), loaded.getUuid(), "UUID should match");
			assertNotNull(loaded.getFile(), "File info should be present");
			assertNotNull(loaded.getFile().getMimeType(), "MIME type should be set");
			log.info("Loaded asset: {} ({})", loaded.getFile().getFilename(), loaded.getFile().getMimeType());

			// Verify tags are populated from demo data
			assertNotNull(loaded.getTags(), "Tags list should not be null");
		}
	}

	/**
	 * Full E2E: run Playwright asset tests from the loom-ui directory.
	 */
	@Test
	void testAssetsViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright asset test.");
			return;
		}
		log.info("Using loom-ui at {}", loomUiDir.getAbsolutePath());

		String apiBaseUrl = "/api/v1";
		String proxyTarget = "http://localhost:" + REST_PORT;
		int vitePort = findFreePort();
		log.info("Running Playwright asset e2e tests (Vite on port {}, proxy to {})", vitePort, proxyTarget);

		ProcessBuilder ppb = new ProcessBuilder(
			"npx", "playwright", "test", "e2e/assets-backend.spec.ts", "--reporter=list");
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
				log.info("[playwright-assets] {}", line);
			}
		}

		boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
		if (!finished) {
			proc.destroyForcibly();
			throw new AssertionError("Playwright asset tests timed out after 120s");
		}

		assertEquals(0, proc.exitValue(),
			"Playwright asset tests failed (exit code " + proc.exitValue() + "):\n" + output);
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
			"npx", "playwright", "test", "e2e/login-backend.spec.ts", "--reporter=list");
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

	/**
	 * Verify tag CRUD via REST API: list, create, update, delete.
	 */
	@Test
	void testTagCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing tags (demo data)
			TagListResponse listResp = client.listTags().sync().body();
			assertNotNull(listResp, "Tag list response should not be null");
			assertNotNull(listResp.getData(), "Tag list data should not be null");
			assertFalse(listResp.getData().isEmpty(), "Tag list should contain demo tags");
			int initialCount = listResp.getData().size();
			log.info("Initial tag count: {}", initialCount);

			// Create a new tag
			TagCreateRequest createReq = new TagCreateRequest();
			createReq.setName("e2e-test-tag");
			createReq.setCollection("test");
			TagResponse created = client.createTag(createReq).sync().body();
			assertNotNull(created, "Created tag should not be null");
			assertNotNull(created.getUuid(), "Created tag UUID should not be null");
			assertEquals("e2e-test-tag", created.getName());
			assertEquals("test", created.getCollection());
			log.info("Created tag: {} ({})", created.getName(), created.getUuid());

			// Verify the tag appears in the listing
			TagListResponse listAfterCreate = client.listTags().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Tag list should have grown after create");

			// Load the tag by UUID
			TagResponse loaded = client.loadTag(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded tag should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-tag", loaded.getName());

			// Delete the tag
			client.deleteTag(created.getUuid()).sync().body();

			// Verify the tag is gone
			TagListResponse listAfterDelete = client.listTags().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Tag count should return to initial after delete");
			log.info("Tag CRUD test passed");
		}
	}

	/**
	 * Verify asset pool CRUD via REST API: list demo pools, create, load, update, delete.
	 */
	@Test
	void testAssetPoolCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing pools (demo data should contain 3)
			AssetPoolListResponse listResp = client.listPools().sync().body();
			assertNotNull(listResp, "Pool list response should not be null");
			assertNotNull(listResp.getData(), "Pool list data should not be null");
			assertTrue(listResp.getData().size() >= 3, "Pool list should contain at least 3 demo pools");
			int initialCount = listResp.getData().size();
			log.info("Initial pool count: {}", initialCount);

			// Create a new filesystem pool
			AssetPoolCreateRequest createReq = new AssetPoolCreateRequest();
			createReq.setName("e2e-test-pool");
			createReq.setFsPath("/tmp/e2e-test");
			AssetPoolResponse created = client.createPool(createReq).sync().body();
			assertNotNull(created, "Created pool should not be null");
			assertNotNull(created.getUuid(), "Created pool UUID should not be null");
			assertEquals("e2e-test-pool", created.getName());
			assertEquals("/tmp/e2e-test", created.getFsPath());
			log.info("Created pool: {} ({})", created.getName(), created.getUuid());

			// Verify the pool appears in the listing
			AssetPoolListResponse listAfterCreate = client.listPools().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Pool list should have grown after create");

			// Load the pool by UUID
			AssetPoolResponse loaded = client.loadPool(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded pool should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-pool", loaded.getName());

			// Delete the pool
			client.deletePool(created.getUuid()).sync().body();

			// Verify the pool is gone
			AssetPoolListResponse listAfterDelete = client.listPools().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Pool count should return to initial after delete");
			log.info("Asset pool CRUD test passed");
		}
	}

	/**
	 * Verify user CRUD via REST API: list, create, load, update, delete.
	 */
	@Test
	void testUserCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing users (admin + demo users)
			UserListResponse listResp = client.listUsers().sync().body();
			assertNotNull(listResp, "User list response should not be null");
			assertNotNull(listResp.getData(), "User list data should not be null");
			assertFalse(listResp.getData().isEmpty(), "User list should contain at least the admin user");
			int initialCount = listResp.getData().size();
			log.info("Initial user count: {}", initialCount);

			// Create a new user
			UserCreateRequest createReq = new UserCreateRequest();
			createReq.setUsername("e2e-test-user");
			createReq.setFirstname("Test");
			createReq.setLastname("User");
			createReq.setEmail("e2e@example.com");
			UserResponse created = client.createUser(createReq).sync().body();
			assertNotNull(created, "Created user should not be null");
			assertNotNull(created.getUuid(), "Created user UUID should not be null");
			assertEquals("e2e-test-user", created.getUsername());
			assertEquals("Test", created.getFirstname());
			assertEquals("User", created.getLastname());
			assertEquals("e2e@example.com", created.getEmail());
			log.info("Created user: {} ({})", created.getUsername(), created.getUuid());

			// Verify the user appears in the listing
			UserListResponse listAfterCreate = client.listUsers().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "User list should have grown after create");

			// Load the user by UUID
			UserResponse loaded = client.loadUser(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded user should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-user", loaded.getUsername());

			// Update the user
			UserUpdateRequest updateReq = new UserUpdateRequest();
			updateReq.setFirstname("Updated");
			updateReq.setEmail("updated@example.com");
			UserResponse updated = client.updateUser(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated user should not be null");
			assertEquals("Updated", updated.getFirstname());
			assertEquals("updated@example.com", updated.getEmail());

			// Delete the user
			client.deleteUser(created.getUuid()).sync().body();

			// Verify the user is gone
			UserListResponse listAfterDelete = client.listUsers().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "User count should return to initial after delete");
			log.info("User CRUD test passed");
		}
	}

	/**
	 * Verify group CRUD via REST API: list, create, load, update, delete.
	 */
	@Test
	void testGroupCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing groups (demo data)
			GroupListResponse listResp = client.listGroups().sync().body();
			assertNotNull(listResp, "Group list response should not be null");
			assertNotNull(listResp.getData(), "Group list data should not be null");
			int initialCount = listResp.getData().size();
			log.info("Initial group count: {}", initialCount);

			// Create a new group
			GroupCreateRequest createReq = new GroupCreateRequest();
			createReq.setName("e2e-test-group");
			GroupResponse created = client.createGroup(createReq).sync().body();
			assertNotNull(created, "Created group should not be null");
			assertNotNull(created.getUuid(), "Created group UUID should not be null");
			assertEquals("e2e-test-group", created.getName());
			log.info("Created group: {} ({})", created.getName(), created.getUuid());

			// Verify the group appears in the listing
			GroupListResponse listAfterCreate = client.listGroups().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Group list should have grown after create");

			// Load the group by UUID
			GroupResponse loaded = client.loadGroup(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded group should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-group", loaded.getName());

			// Update the group
			GroupUpdateRequest updateReq = new GroupUpdateRequest();
			updateReq.setName("e2e-test-group-updated");
			GroupResponse updated = client.updateGroup(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated group should not be null");
			assertEquals("e2e-test-group-updated", updated.getName());

			// Delete the group
			client.deleteGroup(created.getUuid()).sync().body();

			// Verify the group is gone
			GroupListResponse listAfterDelete = client.listGroups().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Group count should return to initial after delete");
			log.info("Group CRUD test passed");
		}
	}

	/**
	 * Verify role CRUD via REST API: list, create, load, update, delete.
	 */
	@Test
	void testRoleCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing roles (demo data)
			RoleListResponse listResp = client.listRoles().sync().body();
			assertNotNull(listResp, "Role list response should not be null");
			assertNotNull(listResp.getData(), "Role list data should not be null");
			int initialCount = listResp.getData().size();
			log.info("Initial role count: {}", initialCount);

			// Create a new role
			RoleCreateRequest createReq = new RoleCreateRequest();
			createReq.setName("e2e-test-role");
			RoleResponse created = client.createRole(createReq).sync().body();
			assertNotNull(created, "Created role should not be null");
			assertNotNull(created.getUuid(), "Created role UUID should not be null");
			assertEquals("e2e-test-role", created.getName());
			log.info("Created role: {} ({})", created.getName(), created.getUuid());

			// Verify the role appears in the listing
			RoleListResponse listAfterCreate = client.listRoles().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Role list should have grown after create");

			// Load the role by UUID
			RoleResponse loaded = client.loadRole(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded role should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-role", loaded.getName());

			// Update the role
			RoleUpdateRequest updateReq = new RoleUpdateRequest();
			updateReq.setName("e2e-test-role-updated");
			RoleResponse updated = client.updateRole(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated role should not be null");
			assertEquals("e2e-test-role-updated", updated.getName());

			// Delete the role
			client.deleteRole(created.getUuid()).sync().body();

			// Verify the role is gone
			RoleListResponse listAfterDelete = client.listRoles().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Role count should return to initial after delete");
			log.info("Role CRUD test passed");
		}
	}

	/**
	 * Full E2E: run Playwright tag tests from the loom-ui directory.
	 */
	@Test
	void testTagsViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright tag test.");
			return;
		}
		log.info("Using loom-ui at {}", loomUiDir.getAbsolutePath());

		String apiBaseUrl = "/api/v1";
		String proxyTarget = "http://localhost:" + REST_PORT;
		int vitePort = findFreePort();
		log.info("Running Playwright tag e2e tests (Vite on port {}, proxy to {})", vitePort, proxyTarget);

		ProcessBuilder ppb = new ProcessBuilder(
			"npx", "playwright", "test", "e2e/tags-backend.spec.ts", "--reporter=list");
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
				log.info("[playwright-tags] {}", line);
			}
		}

		boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
		if (!finished) {
			proc.destroyForcibly();
			throw new AssertionError("Playwright tag tests timed out after 120s");
		}

		assertEquals(0, proc.exitValue(),
			"Playwright tag tests failed (exit code " + proc.exitValue() + "):\n" + output);
	}

	/**
	 * Full E2E: run Playwright users CRUD tests from the loom-ui directory.
	 */
	@Test
	void testUsersViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright users test.");
			return;
		}
		runPlaywrightSpec(loomUiDir, "e2e/users-backend.spec.ts", "playwright-users");
	}

	/**
	 * Full E2E: run Playwright groups CRUD tests from the loom-ui directory.
	 */
	@Test
	void testGroupsViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright groups test.");
			return;
		}
		runPlaywrightSpec(loomUiDir, "e2e/groups-backend.spec.ts", "playwright-groups");
	}

	/**
	 * Full E2E: run Playwright roles CRUD tests from the loom-ui directory.
	 */
	@Test
	void testRolesViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright roles test.");
			return;
		}
		runPlaywrightSpec(loomUiDir, "e2e/roles-backend.spec.ts", "playwright-roles");
	}

	private void runPlaywrightSpec(File loomUiDir, String specPath, String logPrefix) throws Exception {
		String apiBaseUrl = "/api/v1";
		String proxyTarget = "http://localhost:" + REST_PORT;
		int vitePort = findFreePort();
		log.info("Running Playwright {} tests (Vite on port {}, proxy to {})", logPrefix, vitePort, proxyTarget);

		ProcessBuilder ppb = new ProcessBuilder(
			"npx", "playwright", "test", specPath, "--reporter=list");
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
				log.info("[{}] {}", logPrefix, line);
			}
		}

		boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
		if (!finished) {
			proc.destroyForcibly();
			throw new AssertionError("Playwright " + logPrefix + " tests timed out after 120s");
		}

		assertEquals(0, proc.exitValue(),
			"Playwright " + logPrefix + " tests failed (exit code " + proc.exitValue() + "):\n" + output);
	}

	/**
	 * (Re)create the loom database and user using the PostgreSQL admin credentials (postgres / finger). This ensures a clean state regardless of what happened
	 * in previous runs.
	 */
	private static void setupDatabase() throws Exception {
		String adminJdbcUrl = "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/postgres";
		log.info("Setting up database via admin connection: {} (user={})", adminJdbcUrl, PG_ADMIN_USER);

		try (Connection conn = DriverManager.getConnection(adminJdbcUrl, PG_ADMIN_USER, PG_ADMIN_PASS);
			Statement stmt = conn.createStatement()) {

			// Terminate active connections to the target database
			stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + DB_NAME + "' AND pid <> pg_backend_pid()");

			// Drop and recreate the database
			stmt.execute("DROP DATABASE IF EXISTS " + DB_NAME);
			log.info("Dropped database '{}' (if it existed)", DB_NAME);

			// Ensure the application role exists
			var rs = stmt.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = '" + DB_USER + "'");
			if (!rs.next()) {
				stmt.execute("CREATE USER " + DB_USER + " WITH PASSWORD '" + DB_PASS + "' SUPERUSER");
				log.info("Created database role '{}'", DB_USER);
			}
			rs.close();

			stmt.execute("CREATE DATABASE " + DB_NAME + " OWNER " + DB_USER);
			log.info("Created fresh database '{}'", DB_NAME);
		}
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
		throw new IllegalStateException("Cannot find loom-demo.jar. Set -Dloom.jar=<path> or build the space first.");
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
