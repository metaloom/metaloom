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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.metaloom.loom.api.asset.AssetId;
import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.auth.AuthLoginResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistCreateRequest;
import io.metaloom.loom.rest.model.blacklist.BlacklistListResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistResponse;
import io.metaloom.loom.rest.model.blacklist.BlacklistUpdateRequest;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentListResponse;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.comment.CommentUpdateRequest;
import io.metaloom.loom.rest.model.group.GroupCreateRequest;
import io.metaloom.loom.rest.model.group.GroupListResponse;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.group.GroupUpdateRequest;
import io.metaloom.loom.rest.model.library.LibraryCreateRequest;
import io.metaloom.loom.rest.model.library.LibraryListResponse;
import io.metaloom.loom.rest.model.library.LibraryResponse;
import io.metaloom.loom.rest.model.library.LibraryUpdateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolCreateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolListResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.model.collection.CollectionCreateRequest;
import io.metaloom.loom.rest.model.collection.CollectionListResponse;
import io.metaloom.loom.rest.model.collection.CollectionResponse;
import io.metaloom.loom.rest.model.collection.CollectionUpdateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionCreateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionListResponse;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;
import io.metaloom.loom.rest.model.tag.TagCreateRequest;
import io.metaloom.loom.rest.model.tag.TagListResponse;
import io.metaloom.loom.rest.model.tag.TagResponse;
import io.metaloom.loom.rest.model.task.TaskCreateRequest;
import io.metaloom.loom.rest.model.task.TaskListResponse;
import io.metaloom.loom.rest.model.task.TaskResponse;
import io.metaloom.loom.rest.model.task.TaskUpdateRequest;
import io.metaloom.loom.rest.model.token.TokenCreateRequest;
import io.metaloom.loom.rest.model.token.TokenListResponse;
import io.metaloom.loom.rest.model.token.TokenResponse;
import io.metaloom.loom.rest.model.token.TokenUpdateRequest;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;

/**
 * End-to-end test that verifies the running Loom backend through the real REST API.
 */
public class E2ETest {

	private static final Logger log = LoggerFactory.getLogger(E2ETest.class);

	private static final int REST_PORT = 8092;

	@BeforeAll
	static void startLoom() throws Exception {
		log.info("Using externally managed Loom backend on localhost:{}", REST_PORT);
		waitForRestApi(Duration.ofSeconds(120));
		log.info("Loom REST API available at localhost:{}", REST_PORT);
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
	 * Verify library CRUD via REST API: list demo libraries, create, load, update, delete.
	 */
	@Test
	void testLibraryCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			LibraryListResponse listResp = client.listLibraries().sync().body();
			assertNotNull(listResp, "Library list response should not be null");
			int initialCount = listResp.getData() != null ? listResp.getData().size() : 0;
			log.info("Initial library count: {}", initialCount);

			LibraryCreateRequest createReq = new LibraryCreateRequest();
			createReq.setName("e2e-test-library");
			LibraryResponse created = client.createLibrary(createReq).sync().body();
			assertNotNull(created, "Created library should not be null");
			assertNotNull(created.getUuid(), "Created library UUID should not be null");
			assertEquals("e2e-test-library", created.getName());

			LibraryListResponse listAfterCreate = client.listLibraries().sync().body();
			assertNotNull(listAfterCreate.getData(), "Library list data should not be null after create");
			assertTrue(listAfterCreate.getData().size() > initialCount, "Library list should have grown after create");

			LibraryResponse loaded = client.loadLibrary(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded library should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-library", loaded.getName());

			LibraryUpdateRequest updateReq = new LibraryUpdateRequest();
			updateReq.setName("e2e-test-library-updated");
			LibraryResponse updated = client.updateLibrary(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated library should not be null");
			assertEquals("e2e-test-library-updated", updated.getName());

			client.deleteLibrary(created.getUuid()).sync().body();

			LibraryListResponse listAfterDelete = client.listLibraries().sync().body();
			assertEquals(initialCount, listAfterDelete.getData() != null ? listAfterDelete.getData().size() : 0,
				"Library count should return to initial after delete");
			log.info("Library CRUD test passed");
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

			// Create a new filesystem pool with freeSpace and usedSpace
			AssetPoolCreateRequest createReq = new AssetPoolCreateRequest();
			createReq.setName("e2e-test-pool");
			createReq.setFsPath("/tmp/e2e-test");
			createReq.setFreeSpace(1024L * 1024 * 1024 * 100); // 100 GB
			createReq.setUsedSpace(1024L * 1024 * 1024 * 50); // 50 GB
			AssetPoolResponse created = client.createPool(createReq).sync().body();
			assertNotNull(created, "Created pool should not be null");
			assertNotNull(created.getUuid(), "Created pool UUID should not be null");
			assertEquals("e2e-test-pool", created.getName());
			assertEquals("/tmp/e2e-test", created.getFsPath());
			assertEquals(Long.valueOf(1024L * 1024 * 1024 * 100), created.getFreeSpace(), "Free space should be set");
			assertEquals(Long.valueOf(1024L * 1024 * 1024 * 50), created.getUsedSpace(), "Used space should be set");
			log.info("Created pool: {} ({})", created.getName(), created.getUuid());

			// Verify the pool appears in the listing
			AssetPoolListResponse listAfterCreate = client.listPools().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Pool list should have grown after create");

			// Load the pool by UUID
			AssetPoolResponse loaded = client.loadPool(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded pool should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-pool", loaded.getName());
			assertEquals(Long.valueOf(1024L * 1024 * 1024 * 100), loaded.getFreeSpace(), "Free space should persist");
			assertEquals(Long.valueOf(1024L * 1024 * 1024 * 50), loaded.getUsedSpace(), "Used space should persist");

			// Delete the pool
			client.deletePool(created.getUuid()).sync().body();

			// Verify the pool is gone
			AssetPoolListResponse listAfterDelete = client.listPools().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Pool count should return to initial after delete");
			log.info("Asset pool CRUD test passed");
		}
	}

	/**
	 * Verify collection CRUD via REST API: list, create, load, update, delete.
	 */
	@Test
	void testCollectionCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing collections
			CollectionListResponse listResp = client.listCollections().sync().body();
			assertNotNull(listResp, "Collection list response should not be null");
			assertNotNull(listResp.getData(), "Collection list data should not be null");
			int initialCount = listResp.getData().size();
			log.info("Initial collection count: {}", initialCount);

			// Create a new collection
			CollectionCreateRequest createReq = new CollectionCreateRequest();
			createReq.setName("e2e-test-collection");
			CollectionResponse created = client.createCollection(createReq).sync().body();
			assertNotNull(created, "Created collection should not be null");
			assertNotNull(created.getUuid(), "Created collection UUID should not be null");
			assertEquals("e2e-test-collection", created.getName());
			log.info("Created collection: {} ({})", created.getName(), created.getUuid());

			// Verify the collection appears in the listing
			CollectionListResponse listAfterCreate = client.listCollections().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Collection list should have grown after create");

			// Load the collection by UUID
			CollectionResponse loaded = client.loadCollection(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded collection should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-collection", loaded.getName());

			// Update the collection name
			CollectionUpdateRequest updateReq = new CollectionUpdateRequest();
			updateReq.setName("e2e-test-collection-updated");
			CollectionResponse updated = client.updateCollection(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated collection should not be null");
			assertEquals("e2e-test-collection-updated", updated.getName());

			// Verify update persisted
			CollectionResponse reloaded = client.loadCollection(created.getUuid()).sync().body();
			assertEquals("e2e-test-collection-updated", reloaded.getName());

			// Delete the collection
			client.deleteCollection(created.getUuid()).sync().body();

			// Verify the collection is gone
			CollectionListResponse listAfterDelete = client.listCollections().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Collection count should return to initial after delete");
			log.info("Collection CRUD test passed");
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
	 * Full E2E: run Playwright library CRUD tests from the loom-ui directory.
	 */
	@Test
	void testLibraryViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright library test.");
			return;
		}
		runPlaywrightSpec(loomUiDir, "e2e/library-backend.spec.ts", "playwright-library");
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

	/**
	 * Full E2E: run Playwright asset pools CRUD tests from the loom-ui directory.
	 */
	@Test
	void testAssetPoolsViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright asset pools test.");
			return;
		}
		runPlaywrightSpec(loomUiDir, "e2e/pools-backend.spec.ts", "playwright-pools");
	}

	/**
	 * Full E2E: run Playwright collections CRUD tests from the loom-ui directory.
	 */
	@Test
	void testCollectionsViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright collections test.");
			return;
		}
		runPlaywrightSpec(loomUiDir, "e2e/collections-backend.spec.ts", "playwright-collections");
	}

	/**
	 * Full E2E: run Playwright pipeline editor tests from the loom-ui directory.
	 */
	@Test
	void testPipelineViaPlaywright() throws Exception {
		File loomUiDir = resolveLoomUiDir();
		if (loomUiDir == null) {
			log.warn("loom-ui directory not found. Skipping Playwright pipeline test.");
			return;
		}
		runPlaywrightSpec(loomUiDir, "e2e/pipeline-backend.spec.ts", "playwright-pipeline");
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
			Thread.sleep(1000);
		}
		throw new IllegalStateException("Loom REST API did not become available within " + timeout);
	}

	/**
	 * Verify reaction CRUD on assets via REST API: create, list, load, delete.
	 */
	@Test
	void testReactionCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// Pick the first demo asset
			AssetListResponse assetList = client.listAssets().sync().body();
			assertFalse(assetList.getData().isEmpty(), "Need at least one asset for reaction test");
			AssetResponse asset = assetList.getData().get(0);
			AssetId assetId = AssetId.assetId(asset.getUuid());

			// Clear whatever is already on the asset. A reaction is unique per (asset, creator, type), and the
			// Playwright backend specs in this same run react to the same first asset - so re-creating one here
			// is a duplicate, which the server now correctly refuses with a 409.
			ReactionListResponse initialReactions = client.listAssetReaction(assetId).sync().body();
			if (initialReactions.getData() != null) {
				for (ReactionResponse existing : initialReactions.getData()) {
					client.deleteAssetReaction(assetId, existing.getUuid()).sync();
				}
			}
			int initialCount = 0;

			// Create a reaction
			ReactionCreateRequest createReq = new ReactionCreateRequest();
			createReq.setType(ReactionType.THUMBSUP);
			ReactionResponse created = client.createAssetReaction(assetId, createReq).sync().body();
			assertNotNull(created, "Created reaction should not be null");
			assertNotNull(created.getUuid(), "Created reaction UUID should not be null");
			assertEquals(ReactionType.THUMBSUP, created.getType());
			log.info("Created reaction: {} ({})", created.getType(), created.getUuid());

			// Verify the reaction appears in the listing
			ReactionListResponse listAfterCreate = client.listAssetReaction(assetId).sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Reaction list should have grown after create");

			// Load the reaction by UUID
			ReactionResponse loaded = client.loadAssetReaction(assetId, created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded reaction should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals(ReactionType.THUMBSUP, loaded.getType());

			// Delete the reaction
			client.deleteAssetReaction(assetId, created.getUuid()).sync().body();

			// Verify the reaction is gone. Deleting the last reaction leaves the listing empty, and an empty list
			// response carries no data array at all (AbstractListResponse#setData) - the same guard line 869 uses.
			ReactionListResponse listAfterDelete = client.listAssetReaction(assetId).sync().body();
			int remaining = listAfterDelete.getData() != null ? listAfterDelete.getData().size() : 0;
			assertEquals(initialCount, remaining, "Reaction count should return to initial after delete");
			log.info("Reaction CRUD test passed");
		}
	}

	/**
	 * Verify token (API key) CRUD via REST API: list, create, load, update, delete.
	 */
	@Test
	void testTokenCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing tokens (demo data should have created some)
			TokenListResponse listResp = client.listTokens().sync().body();
			assertNotNull(listResp, "Token list response should not be null");
			assertNotNull(listResp.getData(), "Token list data should not be null");
			int initialCount = listResp.getData().size();

			// Create a token
			TokenCreateRequest createReq = new TokenCreateRequest();
			createReq.setName("e2e-test-token");
			TokenResponse created = client.createToken(createReq).sync().body();
			assertNotNull(created, "Created token should not be null");
			assertNotNull(created.getUuid(), "Created token UUID should not be null");
			assertEquals("e2e-test-token", created.getName());
			assertNotNull(created.getToken(), "Created token value should not be null");
			log.info("Created token: {} ({})", created.getName(), created.getUuid());

			// Verify the token appears in the listing
			TokenListResponse listAfterCreate = client.listTokens().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Token list should have grown after create");

			// Load the token by UUID
			TokenResponse loaded = client.loadToken(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded token should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-token", loaded.getName());

			// Update the token
			TokenUpdateRequest updateReq = new TokenUpdateRequest();
			updateReq.setName("e2e-test-token-updated");
			TokenResponse updated = client.updateToken(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated token should not be null");

			// Delete the token
			client.deleteToken(created.getUuid()).sync().body();

			// Verify the token is gone
			TokenListResponse listAfterDelete = client.listTokens().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Token count should return to initial after delete");
			log.info("Token CRUD test passed");
		}
	}

	/**
	 * Verify task CRUD via REST API: list, create, load, update, delete.
	 */
	@Test
	void testTaskCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing tasks
			TaskListResponse listResp = client.listTasks().sync().body();
			assertNotNull(listResp, "Task list response should not be null");
			assertNotNull(listResp.getData(), "Task list data should not be null");
			int initialCount = listResp.getData().size();
			log.info("Initial task count: {}", initialCount);

			// Create a new task
			TaskCreateRequest createReq = new TaskCreateRequest();
			createReq.setTitle("e2e-test-task");
			createReq.setDescription("Created by E2E test");
			TaskResponse created = client.createTask(createReq).sync().body();
			assertNotNull(created, "Created task should not be null");
			assertNotNull(created.getUuid(), "Created task UUID should not be null");
			assertEquals("e2e-test-task", created.getTitle());
			log.info("Created task: {} ({})", created.getTitle(), created.getUuid());

			// Verify the task appears in the listing
			TaskListResponse listAfterCreate = client.listTasks().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Task list should have grown after create");

			// Load the task by UUID
			TaskResponse loaded = client.loadTask(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded task should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-task", loaded.getTitle());

			// Update the task
			TaskUpdateRequest updateReq = new TaskUpdateRequest();
			updateReq.setTitle("e2e-test-task-updated");
			TaskResponse updated = client.updateTask(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated task should not be null");
			assertEquals("e2e-test-task-updated", updated.getTitle());

			// Delete the task
			client.deleteTask(created.getUuid()).sync().body();

			// Verify the task is gone
			TaskListResponse listAfterDelete = client.listTasks().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Task count should return to initial after delete");
			log.info("Task CRUD test passed");
		}
	}

	/**
	 * Verify comment CRUD via REST API: list, create, load, update, delete.
	 */
	@Test
	void testCommentCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// List existing comments
			CommentListResponse listResp = client.listComments().sync().body();
			assertNotNull(listResp, "Comment list response should not be null");
			assertNotNull(listResp.getData(), "Comment list data should not be null");
			int initialCount = listResp.getData().size();
			log.info("Initial comment count: {}", initialCount);

			// Create a new comment
			CommentCreateRequest createReq = new CommentCreateRequest();
			createReq.setTitle("e2e-test-comment");
			createReq.setText("Created by E2E test");
			CommentResponse created = client.createComment(createReq).sync().body();
			assertNotNull(created, "Created comment should not be null");
			assertNotNull(created.getUuid(), "Created comment UUID should not be null");
			assertEquals("e2e-test-comment", created.getTitle());
			log.info("Created comment: {} ({})", created.getTitle(), created.getUuid());

			// Verify the comment appears in the listing
			CommentListResponse listAfterCreate = client.listComments().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Comment list should have grown after create");

			// Load the comment by UUID
			CommentResponse loaded = client.loadComment(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded comment should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-comment", loaded.getTitle());

			// Update the comment
			CommentUpdateRequest updateReq = new CommentUpdateRequest();
			updateReq.setTitle("e2e-test-comment-updated");
			CommentResponse updated = client.updateComment(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated comment should not be null");
			assertEquals("e2e-test-comment-updated", updated.getTitle());

			// Delete the comment
			client.deleteComment(created.getUuid()).sync().body();

			// Verify the comment is gone
			CommentListResponse listAfterDelete = client.listComments().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Comment count should return to initial after delete");
			log.info("Comment CRUD test passed");
		}
	}

	/**
	 * Verify blacklist CRUD via REST API: list, create, load, update, delete.
	 */
	@Test
	void testBlacklistCRUD() throws Exception {
		try (LoomHttpClient client = LoomHttpClient.builder()
			.setHostname("localhost")
			.setReadTimeout(Duration.ofSeconds(30))
			.setPort(REST_PORT)
			.build()) {

			AuthLoginResponse loginResp = client.login("admin", "finger").sync().body();
			client.setToken(loginResp.getToken());

			// Create a new blacklist entry. An entry blocks one asset and is keyed on (asset_uuid, creator_uuid),
			// so it needs a real asset to point at - a request carrying only a name is rejected as invalid.
			AssetListResponse blacklistAssets = client.listAssets().sync().body();
			assertFalse(blacklistAssets.getData().isEmpty(), "Need at least one asset for the blacklist test");
			String blacklistAssetUuid = blacklistAssets.getData().get(0).getUuid().toString();

			// Drop any entry this user already holds against that asset. The pairing is unique, and the
			// Playwright blacklist spec in this same run targets the same first asset, so a stale entry would
			// turn the create below into a 409.
			BlacklistListResponse listResp = client.listBlacklists().sync().body();
			assertNotNull(listResp, "Blacklist list response should not be null");
			assertNotNull(listResp.getData(), "Blacklist list data should not be null");
			for (BlacklistResponse existing : List.copyOf(listResp.getData())) {
				if (blacklistAssetUuid.equals(existing.getAssetUuid())) {
					client.deleteBlacklist(existing.getUuid()).sync();
				}
			}
			int initialCount = client.listBlacklists().sync().body().getData().size();
			log.info("Initial blacklist count: {}", initialCount);
			BlacklistCreateRequest createReq = new BlacklistCreateRequest();
			createReq.setName("e2e-test-blacklist");
			createReq.setAssetUuid(blacklistAssets.getData().get(0).getUuid().toString());
			BlacklistResponse created = client.createBlacklist(createReq).sync().body();
			assertNotNull(created, "Created blacklist entry should not be null");
			assertNotNull(created.getUuid(), "Created blacklist UUID should not be null");
			assertEquals("e2e-test-blacklist", created.getName());
			log.info("Created blacklist entry: {} ({})", created.getName(), created.getUuid());

			// Verify the entry appears in the listing
			BlacklistListResponse listAfterCreate = client.listBlacklists().sync().body();
			assertTrue(listAfterCreate.getData().size() > initialCount, "Blacklist list should have grown after create");

			// Load the entry by UUID
			BlacklistResponse loaded = client.loadBlacklist(created.getUuid()).sync().body();
			assertNotNull(loaded, "Loaded blacklist entry should not be null");
			assertEquals(created.getUuid(), loaded.getUuid());
			assertEquals("e2e-test-blacklist", loaded.getName());

			// Update the entry
			BlacklistUpdateRequest updateReq = new BlacklistUpdateRequest();
			updateReq.setName("e2e-test-blacklist-updated");
			BlacklistResponse updated = client.updateBlacklist(created.getUuid(), updateReq).sync().body();
			assertNotNull(updated, "Updated blacklist entry should not be null");
			assertEquals("e2e-test-blacklist-updated", updated.getName());

			// Delete the entry
			client.deleteBlacklist(created.getUuid()).sync().body();

			// Verify the entry is gone
			BlacklistListResponse listAfterDelete = client.listBlacklists().sync().body();
			assertEquals(initialCount, listAfterDelete.getData().size(), "Blacklist count should return to initial after delete");
			log.info("Blacklist CRUD test passed");
		}
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
