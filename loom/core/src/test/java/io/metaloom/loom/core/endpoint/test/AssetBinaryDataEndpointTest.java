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

	private String dataUrl(UUID assetUuid) {
		int port = loom.internal().boot().getRestService().getServer().actualPort();
		return "http://localhost:" + port + "/api/v1/assets/" + assetUuid + "/binary/data";
	}

	private String tokenOf(LoomHttpClient client) {
		return client.getToken();
	}
}
