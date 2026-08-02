package io.metaloom.cortex.cloud.gdrive;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.option.GDriveClientOptions;
import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudFileStore.CloudDelta;
import io.metaloom.cortex.cloud.CloudFileStore.CloudPage;
import io.metaloom.cortex.cloud.CloudUri;
import io.metaloom.cortex.cloud.StubHttpServer;
import io.metaloom.cortex.cloud.StubHttpServer.Response;
import io.metaloom.cortex.cloud.auth.CloudTokenSource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The Drive v3 client, driven against a local stub server with captured-shape JSON.
 */
public class GoogleDriveFileStoreTest {

	@TempDir
	Path tempDir;

	private StubHttpServer server;

	private static final CloudTokenSource TOKENS = new CloudTokenSource() {
		@Override
		public String accessToken() {
			return "test-token";
		}

		@Override
		public void invalidate() {
		}

		@Override
		public String accountId() {
			return "ingest@example.iam.gserviceaccount.com";
		}
	};

	@BeforeEach
	public void setup() throws IOException {
		server = new StubHttpServer();
	}

	@AfterEach
	public void teardown() {
		server.close();
	}

	private GoogleDriveFileStore store() {
		return store(false);
	}

	private GoogleDriveFileStore store(boolean exportNativeDocs) {
		GDriveClientOptions options = new GDriveClientOptions()
			.setApiBaseUrl(server.baseUrl())
			.setServiceAccountJson("{}")
			.setExportNativeDocs(exportNativeDocs);
		return new GoogleDriveFileStore(options, TOKENS);
	}

	private static JsonObject file(String id, String name, String mime, Long size, JsonObject extra) {
		JsonObject json = new JsonObject()
			.put("id", id)
			.put("name", name)
			.put("mimeType", mime)
			.put("modifiedTime", "2026-07-01T12:00:00.000Z")
			.put("parents", new JsonArray().add("parent-1"))
			.put("trashed", false);
		if (size != null) {
			json.put("size", size);
		}
		if (extra != null) {
			json.mergeIn(extra);
		}
		return json;
	}

	private static String query(StubHttpServer.Request request) {
		return URLDecoder.decode(request.query() == null ? "" : request.query(), StandardCharsets.UTF_8);
	}

	@Test
	public void testListMapsFilesAndFolders() throws IOException {
		server.enqueueJson(new JsonObject().put("files", new JsonArray()
			.add(file("f1", "clip.mp4", "video/mp4", 100L, new JsonObject().put("md5Checksum", "abc")))
			.add(file("d1", "Holidays", GoogleExportFormats.FOLDER_MIME, null, null)))
			.encode());

		CloudPage page = store().list("my", "parent-1", null, false);

		assertThat(page.entries()).hasSize(2);
		CloudFileRef clip = page.entries().get(0);
		assertThat(clip.fileId()).isEqualTo("f1");
		assertThat(clip.size()).isEqualTo(100);
		assertThat(clip.folder()).isFalse();
		assertThat(clip.parentId()).isEqualTo("parent-1");
		assertThat(page.entries().get(1).folder()).isTrue();
		assertThat(page.hasMore()).isFalse();
	}

	@Test
	public void testTheListQueryScopesToTheFolderAndExcludesTrash() throws IOException {
		server.enqueueJson("{\"files\":[]}");
		store().list("my", "parent-1", null, false);

		String query = query(server.lastRequest());
		assertThat(query).contains("'parent-1' in parents");
		assertThat(query).contains("trashed = false");
	}

	@Test
	public void testIncludeTrashedDropsTheTrashPredicate() throws IOException {
		server.enqueueJson("{\"files\":[]}");
		store().list("my", "parent-1", null, true);

		// The field mask still asks for the trashed flag - it is the q= predicate that must go.
		assertThat(query(server.lastRequest())).doesNotContain("trashed = false");
	}

	@Test
	public void testASharedDriveCarriesTheCorporaParameters() throws IOException {
		server.enqueueJson("{\"files\":[]}");
		store().list("shared-drive-7", null, null, false);

		String query = query(server.lastRequest());
		// Without corpora+driveId the query silently returns only My Drive, with a 200.
		assertThat(query).contains("corpora=drive");
		assertThat(query).contains("driveId=shared-drive-7");
		assertThat(query).contains("includeItemsFromAllDrives=true");
		// A shared drive's root is the drive itself, not the 'root' alias.
		assertThat(query).contains("'shared-drive-7' in parents");
	}

	@Test
	public void testMyDriveRootUsesTheRootAlias() throws IOException {
		server.enqueueJson("{\"files\":[]}");
		store().list(CloudUri.MY_DRIVE, null, null, false);

		assertThat(query(server.lastRequest())).contains("'root' in parents");
	}

	@Test
	public void testPaginationIsCarriedThrough() throws IOException {
		server.enqueueJson(new JsonObject().put("files", new JsonArray()).put("nextPageToken", "tok-2").encode());
		CloudPage page = store().list("my", null, null, false);

		assertThat(page.hasMore()).isTrue();
		assertThat(page.nextPageToken()).isEqualTo("tok-2");
	}

	@Test
	public void testChangeTokenPrefersMd5ThenFallsBackToVersion() throws IOException {
		server.enqueueJson(new JsonObject().put("files", new JsonArray()
			.add(file("f1", "a.mp4", "video/mp4", 1L, new JsonObject().put("md5Checksum", "abc").put("version", "9")))
			.add(file("f2", "b.mp4", "video/mp4", 1L, new JsonObject().put("version", "12"))))
			.encode());

		CloudPage page = store().list("my", null, null, false);

		assertThat(page.entries().get(0).changeToken()).isEqualTo("md5:abc");
		// version is always present, md5Checksum only for binary files - so it is the fallback.
		assertThat(page.entries().get(1).changeToken()).isEqualTo("v:12");
	}

	@Test
	public void testANativeDocIsUnreadableUntilExportIsEnabled() throws IOException {
		String doc = new JsonObject().put("files", new JsonArray()
			.add(file("f1", "Q3 Report", "application/vnd.google-apps.document", null, null))).encode();

		server.enqueueJson(doc);
		CloudFileRef withoutExport = store(false).list("my", null, null, false).entries().get(0);
		assertThat(withoutExport.requiresExport()).isFalse();
		// No size at all: this is the case that must never fall back to downloading.
		assertThat(withoutExport.size()).isEqualTo(-1);

		server.enqueueJson(doc);
		CloudFileRef withExport = store(true).list("my", null, null, false).entries().get(0);
		assertThat(withExport.requiresExport()).isTrue();
		assertThat(withExport.exportMimeType()).isEqualTo("application/pdf");
	}

	@Test
	public void testGetReturnsNullForAMissingFile() throws IOException {
		server.fallback(Response.error(404, "{\"error\":{\"code\":404,\"message\":\"File not found\"}}"));
		assertThat(store().get("my", "nope")).isNull();
	}

	@Test
	public void testStartDeltaTokenReadsTheStartPageToken() throws IOException {
		server.enqueueJson("{\"startPageToken\":\"5000\"}");
		assertThat(store().startDeltaToken("my")).isEqualTo("5000");
	}

	@Test
	public void testDeltaFollowsItsPagesAndReturnsTheNewCursor() throws IOException {
		server.enqueueJson(new JsonObject()
			.put("changes", new JsonArray().add(new JsonObject().put("fileId", "f1")
				.put("file", file("f1", "a.mp4", "video/mp4", 1L, null))))
			.put("nextPageToken", "p2").encode());
		server.enqueueJson(new JsonObject()
			.put("changes", new JsonArray().add(new JsonObject().put("fileId", "f2").put("removed", true)))
			.put("newStartPageToken", "6000").encode());

		CloudDelta delta = store().delta("my", "5000", false);

		assertThat(delta.tokenExpired()).isFalse();
		assertThat(delta.changes()).hasSize(2);
		assertThat(delta.changes().get(0).removed()).isFalse();
		assertThat(delta.changes().get(1).removed()).isTrue();
		assertThat(delta.nextToken()).isEqualTo("6000");
	}

	@Test
	public void testAnExpiredPageTokenIsReportedNotThrown() throws IOException {
		// Drive answers an aged-out page token with a 404. That means "start over", not "failed".
		server.fallback(Response.error(404, "{\"error\":{\"code\":404,\"message\":\"Invalid Value\"}}"));

		CloudDelta delta = store().delta("my", "stale", false);
		assertThat(delta.tokenExpired()).isTrue();
	}

	@Test
	public void testATrashedFileInTheFeedReadsAsARemoval() throws IOException {
		JsonObject trashed = file("f1", "a.mp4", "video/mp4", 1L, new JsonObject().put("trashed", true));
		server.enqueueJson(new JsonObject()
			.put("changes", new JsonArray().add(new JsonObject().put("fileId", "f1").put("file", trashed)))
			.put("newStartPageToken", "6000").encode());

		CloudDelta delta = store().delta("my", "5000", false);
		assertThat(delta.changes().get(0).removed()).isTrue();
	}

	@Test
	public void testDownloadUsesAltMediaForARealFile() throws IOException {
		server.enqueue(Response.ok("bytes"));
		CloudFileRef ref = new CloudFileRef(io.metaloom.cortex.cloud.CloudProviderId.GDRIVE, "my", "f1",
			"a.mp4", null, "video/mp4", "md5:x", 5, 0, false, false, null, true);

		Path target = tempDir.resolve("out.mp4");
		store().download(ref, target);

		assertThat(Files.readString(target)).isEqualTo("bytes");
		assertThat(query(server.lastRequest())).contains("alt=media");
	}

	@Test
	public void testDownloadUsesExportForANativeDoc() throws IOException {
		server.enqueue(Response.ok("%PDF"));
		CloudFileRef ref = new CloudFileRef(io.metaloom.cortex.cloud.CloudProviderId.GDRIVE, "my", "f1",
			"Q3 Report", null, "application/vnd.google-apps.document", "v:1", -1, 0, false, false,
			"application/pdf", true);

		store().download(ref, tempDir.resolve("out.pdf"));

		assertThat(server.lastRequest().path()).endsWith("/export");
		assertThat(query(server.lastRequest())).contains("mimeType=application/pdf");
	}

	@Test
	public void testANotDownloadableFileExplainsTheExportOption() {
		server.fallback(Response.error(403, new JsonObject().put("error", new JsonObject()
			.put("code", 403)
			.put("errors", new JsonArray().add(new JsonObject().put("reason", "fileNotDownloadable"))))
			.encode()));
		CloudFileRef ref = new CloudFileRef(io.metaloom.cortex.cloud.CloudProviderId.GDRIVE, "my", "f1",
			"Q3 Report", null, "application/vnd.google-apps.document", "v:1", -1, 0, false, false, null, true);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> store().download(ref, tempDir.resolve("out")))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("exportNativeDocs");
	}

	@Test
	public void testResolveDriveIdDefaultsToMyDrive() throws IOException {
		assertThat(store().resolveDriveId(null)).isEqualTo(CloudUri.MY_DRIVE);
		assertThat(store().resolveDriveId("  ")).isEqualTo(CloudUri.MY_DRIVE);
		assertThat(store().resolveDriveId("shared-7")).isEqualTo("shared-7");
	}

	@Test
	public void testQueryLiteralsAreEscaped() {
		assertThat(GoogleDriveFileStore.escapeQueryLiteral("it's")).isEqualTo("it\\'s");
		assertThat(GoogleDriveFileStore.escapeQueryLiteral("a\\b")).isEqualTo("a\\\\b");
	}
}
