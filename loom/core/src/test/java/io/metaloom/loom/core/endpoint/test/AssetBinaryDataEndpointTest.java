package io.metaloom.loom.core.endpoint.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractEndpointTest;
import io.metaloom.loom.db.model.asset.AssetBinary;
import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.model.perm.Permission;
import io.metaloom.loom.db.model.pool.AssetPool;
import io.metaloom.loom.db.model.role.Role;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryListResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.test.data.TestValues;

/**
 * The three byte-carrying routes: {@code POST /assets/upload}, {@code POST /assets/:uuid/binary/data} and {@code GET /assets/:uuid/binary/data}.
 *
 * <p>
 * These had no Java coverage at all — {@code AssetBinaryEndpointTest} exercises only the JSON metadata CRUD — which is how the cardinality defect
 * (an asset with two locations returning 500) and the never-reclaimed stored files both survived. Written against the multipart client methods added
 * alongside, so the coverage and the client gap close together.
 * </p>
 */
public class AssetBinaryDataEndpointTest extends AbstractEndpointTest implements TestValues {

	private final java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

	private final Path storageDir;

	public AssetBinaryDataEndpointTest() throws IOException {
		// Configure the inherited extension rather than declaring a second one: a redeclared
		// @RegisterExtension shadows the inherited `loom` field and the base class helpers then talk to
		// a server that was never booted.
		this.storageDir = Files.createTempDirectory("loom-binary-test");
		loom.withOptions(o -> o.getStorage().setUploadDirectory(storageDir.toString()));
	}

	// -- upload --------------------------------------------------------------

	@Test
	public void shouldCreateAnAssetFromUploadedBytes() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			File file = tempFile("hello-bytes", ".bin");

			AssetResponse asset = client.uploadAsset(file, LIBRARY_UUID, "application/octet-stream").sync().body();

			assertThat(asset.getUuid()).isNotNull();
			AssetBinaryResponse binary = client.loadAssetBinary(asset.getUuid()).sync().body();
			assertThat(binary.getStorageType()).isEqualTo("filesystem");
			// No pool on the fixture library, so the bytes land in the process-wide upload directory.
			assertThat(binary.getPoolUuid()).isNull();
			assertThat(binary.getFilesystem().getPath()).startsWith(storageDir.toString());
			assertThat(Files.readString(Path.of(binary.getFilesystem().getPath()))).isEqualTo("hello-bytes");
		}
	}

	@Test
	public void shouldRoundTripBytesThroughDownload() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = client.uploadAsset(tempFile("round-trip", ".bin"), LIBRARY_UUID, "text/plain").sync().body();

			try (LoomBinaryResponse download = client.downloadAssetBinary(asset.getUuid()).sync().body()) {
				assertThat(download.isSuccessful()).isTrue();
				assertThat(new String(download.getStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("round-trip");
			}
		}
	}

	@Test
	public void shouldReplaceTheBytesOfAnExistingBinary() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = client.uploadAsset(tempFile("first-version", ".bin"), LIBRARY_UUID, "text/plain").sync().body();
			String firstPath = client.loadAssetBinary(asset.getUuid()).sync().body().getFilesystem().getPath();

			client.uploadAssetBinary(asset.getUuid(), tempFile("second-version", ".bin"), null, "text/plain").sync().body();

			AssetBinaryResponse binary = client.loadAssetBinary(asset.getUuid()).sync().body();
			assertThat(binary.getFilesystem().getPath()).isNotEqualTo(firstPath);
			assertThat(Files.readString(Path.of(binary.getFilesystem().getPath()))).isEqualTo("second-version");
			// The replaced bytes were referenced by nothing else, so they are reclaimed rather than leaked.
			assertThat(Files.exists(Path.of(firstPath))).isFalse();
			// Still one binary, not two - the upload replaced the row rather than adding one.
			assertThat(client.listAssetBinaries(asset.getUuid()).sync().body().getData()).hasSize(1);
		}
	}

	// -- explicit pool targeting ---------------------------------------------

	@Test
	public void shouldStoreIntoAnExplicitlyNamedPool() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Path poolDir = Files.createTempDirectory("loom-named-pool");
			UUID poolUuid = createPool("named-pool", poolDir);

			// The fixture library has no pool of its own, so without the override these bytes would land in
			// the process-wide upload directory. Naming the pool has to win over that.
			AssetResponse asset = client.uploadAsset(tempFile("pooled-bytes", ".bin"), LIBRARY_UUID, poolUuid, "text/plain").sync().body();

			AssetBinaryResponse binary = client.loadAssetBinary(asset.getUuid()).sync().body();
			assertThat(binary.getPoolUuid()).isEqualTo(poolUuid);
			assertThat(binary.getFilesystem().getPath()).startsWith(poolDir.toString());
			assertThat(binary.getFilesystem().getPath()).doesNotStartWith(storageDir.toString());
			assertThat(Files.readString(Path.of(binary.getFilesystem().getPath()))).isEqualTo("pooled-bytes");
		}
	}

	@Test
	public void shouldRejectAnUnknownPool() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			expect(404, "Not Found", client.uploadAsset(tempFile("no-such-pool", ".bin"), LIBRARY_UUID, UUID.randomUUID(), "text/plain"));
		}
	}

	@Test
	public void shouldRequirePoolPermissionToNameAPool() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			Path poolDir = Files.createTempDirectory("loom-guarded-pool");
			UUID poolUuid = createPool("guarded-pool", poolDir);

			// A user who may create assets but knows nothing about pools. Uploading is open to them; choosing
			// which storage backend the bytes land in is not, because that is an operator decision.
			try (LoomHttpClient uploader = loginWithPermissions("pool-perm", Permission.CREATE_ASSET, Permission.READ_ASSET,
				Permission.READ_ASSET_BINARY)) {
				expect(403, "Forbidden", uploader.uploadAsset(tempFile("denied-pool", ".bin"), LIBRARY_UUID, poolUuid, "text/plain"));

				// The very same upload without the override still succeeds, so the guard is on the pool
				// override alone and does not regress plain uploading.
				AssetResponse asset = uploader.uploadAsset(tempFile("allowed-plain", ".bin"), LIBRARY_UUID, "text/plain").sync().body();
				assertThat(asset.getUuid()).isNotNull();
			}
		}
	}

	@Test
	public void shouldRejectAMalformedPoolUuid() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			String token = tokenOf(client);
			int port = loom.internal().boot().getRestService().getServer().actualPort();

			// A typo must not quietly fall back to the library's pool and store the bytes somewhere the
			// caller did not ask for.
			HttpResponse<String> res = multipartUpload(port, token, "typo-pool", "libraryUuid", LIBRARY_UUID.toString(), "poolUuid", "not-a-uuid");
			assertEquals(400, res.statusCode());
			assertThat(res.body()).contains("poolUuid");
		}
	}

	@Test
	public void shouldTreatABlankPoolUuidAsAbsent() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			String token = tokenOf(client);
			int port = loom.internal().boot().getRestService().getServer().actualPort();

			// A form built by a UI that always emits the field sends an empty string when nothing is chosen.
			// That must mean "use the library's pool", not "pool with a blank uuid".
			HttpResponse<String> res = multipartUpload(port, token, "blank-pool", "libraryUuid", LIBRARY_UUID.toString(), "poolUuid", "");
			assertEquals(201, res.statusCode());
		}
	}

	// -- cardinality (G1) ----------------------------------------------------

	@Test
	public void shouldServeAnAssetThatHasBinariesInSeveralLibraries() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = client.uploadAsset(tempFile("multi-home", ".bin"), LIBRARY_UUID, "text/plain").sync().body();

			// A second location in another library. The schema has permitted this since V2.48 relaxed the
			// unique constraint to (library_uuid, path); the read path used to answer it with a 500.
			UUID otherLibrary = createLibrary("second-library");
			AssetBinary second = daos().assetBinaryDao().createAssetBinary("/elsewhere/multi-home.bin", asset.getUuid(), adminUuid(),
				otherLibrary);
			daos().assetBinaryDao().store(second);

			AssetBinaryListResponse all = client.listAssetBinaries(asset.getUuid()).sync().body();
			assertThat(all.getData()).hasSize(2);

			// The singular route still answers, with the oldest binary.
			AssetBinaryResponse primary = client.loadAssetBinary(asset.getUuid()).sync().body();
			assertThat(primary.getLibraryUuid()).isEqualTo(LIBRARY_UUID);
			// And so does the byte route, which resolves through the same primary lookup.
			try (LoomBinaryResponse download = client.downloadAssetBinary(asset.getUuid()).sync().body()) {
				assertThat(download.isSuccessful()).isTrue();
			}
		}
	}

	@Test
	public void shouldRefuseAnAmbiguousReplace() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = client.uploadAsset(tempFile("ambiguous", ".bin"), LIBRARY_UUID, "text/plain").sync().body();
			UUID otherLibrary = createLibrary("ambiguous-library");
			AssetBinary second = daos().assetBinaryDao().createAssetBinary("/elsewhere/ambiguous.bin", asset.getUuid(), adminUuid(), otherLibrary);
			daos().assetBinaryDao().store(second);

			// Which one should this replace? Guessing would silently overwrite the wrong library's binary.
			expect(400, "Bad Request", client.uploadAssetBinary(asset.getUuid(), tempFile("nope", ".bin"), null, "text/plain"));

			// Naming the library resolves it.
			AssetBinaryResponse replaced = client.uploadAssetBinary(asset.getUuid(), tempFile("targeted", ".bin"), otherLibrary, "text/plain")
				.sync().body();
			assertThat(replaced.getLibraryUuid()).isEqualTo(otherLibrary);
		}
	}

	// -- range requests (G6) -------------------------------------------------

	@Test
	public void shouldHonourARangeRequest() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = client.uploadAsset(tempFile("0123456789", ".bin"), LIBRARY_UUID, "text/plain").sync().body();
			String token = tokenOf(client);

			HttpResponse<String> res = http.send(HttpRequest.newBuilder()
				.uri(URI.create(dataUrl(asset.getUuid())))
				.header("Authorization", "Bearer " + token)
				.header("Range", "bytes=2-5")
				.GET().build(), BodyHandlers.ofString());

			assertEquals(206, res.statusCode());
			assertEquals("2345", res.body());
			assertEquals("bytes 2-5/10", res.headers().firstValue("Content-Range").orElse(null));
		}
	}

	@Test
	public void shouldRejectAnUnsatisfiableRange() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = client.uploadAsset(tempFile("short", ".bin"), LIBRARY_UUID, "text/plain").sync().body();
			String token = tokenOf(client);

			HttpResponse<String> res = http.send(HttpRequest.newBuilder()
				.uri(URI.create(dataUrl(asset.getUuid())))
				.header("Authorization", "Bearer " + token)
				.header("Range", "bytes=500-600")
				.GET().build(), BodyHandlers.ofString());

			assertEquals(416, res.statusCode());
			assertEquals("bytes */5", res.headers().firstValue("Content-Range").orElse(null));
		}
	}

	@Test
	public void shouldAdvertiseRangeSupportOnAFullResponse() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetResponse asset = client.uploadAsset(tempFile("whole", ".bin"), LIBRARY_UUID, "text/plain").sync().body();
			String token = tokenOf(client);

			HttpResponse<String> res = http.send(HttpRequest.newBuilder()
				.uri(URI.create(dataUrl(asset.getUuid())))
				.header("Authorization", "Bearer " + token)
				.GET().build(), BodyHandlers.ofString());

			assertEquals(200, res.statusCode());
			// Without this header no client ever sends a Range, so seeking would stay unavailable.
			assertEquals("bytes", res.headers().firstValue("Accept-Ranges").orElse(null));
		}
	}

	// -- delete and reclaim (G4) ---------------------------------------------

	@Test
	public void shouldResolveRepeatedContentToOneAsset() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// asset.sha512sum is UNIQUE, so the same bytes are the same asset by definition. Creating
			// unconditionally made the second upload fail with a unique violation surfacing as a 500.
			AssetResponse first = client.uploadAsset(tempFile("same-bytes", ".bin"), LIBRARY_UUID, "text/plain").sync().body();
			AssetResponse again = client.uploadAsset(tempFile("same-bytes", ".bin"), LIBRARY_UUID, "text/plain").sync().body();

			assertEquals(first.getUuid(), again.getUuid());
			// And no second location was created for the same library.
			assertThat(client.listAssetBinaries(first.getUuid()).sync().body().getData()).hasSize(1);
		}
	}

	@Test
	public void shouldReclaimBytesOnDeleteButNotWhileShared() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// The same content imported into two libraries: one asset, one stored object, two locations
			// pointing at it. This is what makes an unconditional unlink on delete destructive.
			UUID otherLibrary = createLibrary("shared-content-library");
			AssetResponse asset = client.uploadAsset(tempFile("shared-content", ".bin"), LIBRARY_UUID, "text/plain").sync().body();
			client.uploadAsset(tempFile("shared-content", ".bin"), otherLibrary, "text/plain").sync().body();

			AssetBinaryListResponse binaries = client.listAssetBinaries(asset.getUuid()).sync().body();
			assertThat(binaries.getData()).hasSize(2);
			String path = binaries.getData().get(0).getFilesystem().getPath();
			assertEquals(path, binaries.getData().get(1).getFilesystem().getPath());

			client.deleteBinary(binaries.getData().get(0).getUuid()).sync();
			assertTrue(Files.exists(Path.of(path)), "Shared bytes must survive while another binary references them");

			client.deleteBinary(binaries.getData().get(1).getUuid()).sync();
			assertThat(Files.exists(Path.of(path))).isFalse();
		}
	}

	// -- permissions (G7/G9) -------------------------------------------------

	@Test
	public void shouldRequireCreatePermissionToUpload() throws Exception {
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			AssetResponse asset = admin.uploadAsset(tempFile("guarded", ".bin"), LIBRARY_UUID, "text/plain").sync().body();

			try (LoomHttpClient nobody = loginPermissionlessClient()) {
				expect(403, "Forbidden", nobody.uploadAsset(tempFile("denied", ".bin"), LIBRARY_UUID, "text/plain"));
				expect(403, "Forbidden", nobody.uploadAssetBinary(asset.getUuid(), tempFile("denied", ".bin"), LIBRARY_UUID, "text/plain"));
			}
		}
	}

	@Test
	public void shouldRequireReadPermissionToDownload() throws Exception {
		try (LoomHttpClient admin = loom.httpClient()) {
			loginAdmin(admin);
			AssetResponse asset = admin.uploadAsset(tempFile("secret", ".bin"), LIBRARY_UUID, "text/plain").sync().body();

			try (LoomHttpClient nobody = loginPermissionlessClient()) {
				String token = tokenOf(nobody);
				HttpResponse<String> res = http.send(HttpRequest.newBuilder()
					.uri(URI.create(dataUrl(asset.getUuid())))
					.header("Authorization", "Bearer " + token)
					.GET().build(), BodyHandlers.ofString());
				assertEquals(403, res.statusCode());

				expect(403, "Forbidden", nobody.listAssetBinaries(asset.getUuid()));
			}
		}
	}

	// -- helpers -------------------------------------------------------------

	private File tempFile(String content, String suffix) throws IOException {
		Path file = Files.createTempFile("upload", suffix);
		Files.writeString(file, content, StandardCharsets.UTF_8);
		file.toFile().deleteOnExit();
		return file.toFile();
	}

	private UUID createLibrary(String name) {
		var library = daos().libraryDao().createLibrary(daos().userDao().loadAdmin(), name);
		daos().libraryDao().store(library);
		return library.getUuid();
	}

	/** A filesystem-backed pool rooted at the given directory. */
	private UUID createPool(String name, Path fsPath) {
		AssetPool pool = daos().assetPoolDao().createAssetPool(adminUuid(), name);
		pool.setFsPath(fsPath.toString());
		daos().assetPoolDao().store(pool);
		return pool.getUuid();
	}

	/**
	 * A fresh enabled user holding exactly the listed permissions, and a client logged in as them.
	 *
	 * <p>
	 * The permissions go through a group + role rather than direct user grants: {@code user_permission} is keyed by user alone, so only one direct
	 * grant per user is possible.
	 * </p>
	 */
	private LoomHttpClient loginWithPermissions(String username, Permission... permissions) throws Exception {
		User user = daos().userDao().createUser(adminUuid(), username);
		user.enable();
		user.setPasswordHash(loom.internal().authService().encodePassword("secret"));
		daos().userDao().store(user);

		Role role = daos().roleDao().createRole(adminUuid(), username + "-role");
		daos().roleDao().store(role);
		for (Permission perm : permissions) {
			daos().permissionDao().grantRolePermission(role.getUuid(), perm);
		}
		Group group = daos().groupDao().create(user, username + "-group");
		daos().groupDao().store(group);
		daos().groupDao().addRoleToGroup(group, role);
		daos().groupDao().addUserToGroup(group, user);

		LoomHttpClient client = loom.httpClient();
		client.setToken(client.login(username, "secret").sync().body().getToken());
		return client;
	}

	/**
	 * A hand-rolled multipart upload, for the cases the typed client cannot express: a {@code poolUuid} that is not a valid uuid, and one sent as an
	 * empty string.
	 */
	private HttpResponse<String> multipartUpload(int port, String token, String content, String... formFields) throws Exception {
		String boundary = "loom-test-boundary";
		StringBuilder body = new StringBuilder();
		for (int i = 0; i + 1 < formFields.length; i += 2) {
			body.append("--").append(boundary).append("\r\n")
				.append("Content-Disposition: form-data; name=\"").append(formFields[i]).append("\"\r\n\r\n")
				.append(formFields[i + 1]).append("\r\n");
		}
		body.append("--").append(boundary).append("\r\n")
			.append("Content-Disposition: form-data; name=\"file\"; filename=\"upload.bin\"\r\n")
			.append("Content-Type: text/plain\r\n\r\n")
			.append(content).append("\r\n")
			.append("--").append(boundary).append("--\r\n");

		return http.send(HttpRequest.newBuilder()
			.uri(URI.create("http://localhost:" + port + "/api/v1/assets/upload"))
			.header("Authorization", "Bearer " + token)
			.header("Content-Type", "multipart/form-data; boundary=" + boundary)
			.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
			.build(), BodyHandlers.ofString());
	}

	private String dataUrl(UUID assetUuid) {
		int port = loom.internal().boot().getRestService().getServer().actualPort();
		return "http://localhost:" + port + "/api/v1/assets/" + assetUuid + "/binary/data";
	}

	private String tokenOf(LoomHttpClient client) {
		return client.getToken();
	}
}
