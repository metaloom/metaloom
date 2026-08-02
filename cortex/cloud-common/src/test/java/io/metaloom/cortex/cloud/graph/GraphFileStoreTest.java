package io.metaloom.cortex.cloud.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.metaloom.cortex.api.option.OneDriveClientOptions;
import io.metaloom.cortex.cloud.CloudFileRef;
import io.metaloom.cortex.cloud.CloudFileStore.CloudDelta;
import io.metaloom.cortex.cloud.CloudFileStore.CloudPage;
import io.metaloom.cortex.cloud.CloudProviderId;
import io.metaloom.cortex.cloud.StubHttpServer;
import io.metaloom.cortex.cloud.StubHttpServer.Response;
import io.metaloom.cortex.cloud.auth.CloudTokenSource;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The Microsoft Graph client, driven against a local stub server with captured-shape JSON.
 */
public class GraphFileStoreTest {

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
			return "tenant-1/cid";
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

	private GraphFileStore store() {
		return new GraphFileStore(new OneDriveClientOptions()
			.setApiBaseUrl(server.baseUrl())
			.setTenantId("tenant-1").setClientId("cid").setClientSecret("secret"), TOKENS);
	}

	private static JsonObject item(String id, String name, Long size, boolean folder) {
		JsonObject json = new JsonObject()
			.put("id", id)
			.put("name", name)
			.put("cTag", "ctag-" + id)
			.put("eTag", "etag-" + id)
			.put("lastModifiedDateTime", "2026-07-01T12:00:00Z")
			.put("parentReference", new JsonObject().put("id", "parent-1"));
		if (size != null) {
			json.put("size", size);
		}
		if (folder) {
			json.put("folder", new JsonObject().put("childCount", 2));
		} else {
			json.put("file", new JsonObject().put("mimeType", "video/mp4"));
		}
		return json;
	}

	@Test
	public void testChildrenMapping() throws IOException {
		server.enqueueJson(new JsonObject().put("value", new JsonArray()
			.add(item("f1", "clip.mp4", 100L, false))
			.add(item("d1", "Holidays", 0L, true))).encode());

		CloudPage page = store().list("drive-1", "parent-1", null, false);

		assertThat(page.entries()).hasSize(2);
		CloudFileRef clip = page.entries().get(0);
		assertThat(clip.provider()).isEqualTo(CloudProviderId.ONEDRIVE);
		assertThat(clip.mimeType()).isEqualTo("video/mp4");
		assertThat(clip.size()).isEqualTo(100);
		assertThat(clip.parentId()).isEqualTo("parent-1");
		// Every OneDrive item has real bytes, so nothing ever needs exporting.
		assertThat(clip.requiresExport()).isFalse();
		assertThat(page.entries().get(1).folder()).isTrue();
	}

	@Test
	public void testRootAndFolderPathsDiffer() throws IOException {
		server.enqueueJson("{\"value\":[]}");
		store().list("drive-1", null, null, false);
		assertThat(server.lastRequest().path()).isEqualTo("/drives/drive-1/root/children");

		server.enqueueJson("{\"value\":[]}");
		store().list("drive-1", "item-9", null, false);
		assertThat(server.lastRequest().path()).isEqualTo("/drives/drive-1/items/item-9/children");
	}

	@Test
	public void testNextLinkIsCarriedAsThePageTokenAndUsedVerbatim() throws IOException {
		String nextLink = server.baseUrl() + "/drives/drive-1/root/children?$skiptoken=abc";
		server.enqueueJson(new JsonObject().put("value", new JsonArray())
			.put("@odata.nextLink", nextLink).encode());

		CloudPage page = store().list("drive-1", null, null, false);
		assertThat(page.nextPageToken()).isEqualTo(nextLink);

		// The whole absolute link is reused, so $select and $top survive without rebuilding them.
		server.enqueueJson("{\"value\":[]}");
		store().list("drive-1", null, page.nextPageToken(), false);
		assertThat(server.lastRequest().query()).contains("$skiptoken=abc");
	}

	@Test
	public void testDeletedItemsAreSkippedInAListing() throws IOException {
		JsonObject deleted = item("f2", "gone.mp4", 1L, false).put("deleted", new JsonObject().put("state", "deleted"));
		server.enqueueJson(new JsonObject().put("value", new JsonArray()
			.add(item("f1", "clip.mp4", 1L, false)).add(deleted)).encode());

		assertThat(store().list("drive-1", null, null, false).entries()).hasSize(1);
	}

	@Test
	public void testChangeTokenPrefersCTag() throws IOException {
		server.enqueueJson(new JsonObject().put("value", new JsonArray()
			.add(item("f1", "a.mp4", 1L, false))).encode());
		assertThat(store().list("drive-1", null, null, false).entries().get(0).changeToken())
			.isEqualTo("ctag:ctag-f1");

		// cTag changes only on a content change; eTag also on metadata edits, so a rename should
		// read as MOVED rather than MODIFIED.
		JsonObject noCTag = item("f2", "b.mp4", 1L, false);
		noCTag.remove("cTag");
		server.enqueueJson(new JsonObject().put("value", new JsonArray().add(noCTag)).encode());
		assertThat(store().list("drive-1", null, null, false).entries().get(0).changeToken())
			.isEqualTo("etag:etag-f2");
	}

	@Test
	public void testDeltaLinkTokenIsExtracted() throws IOException {
		server.enqueueJson(new JsonObject().put("value", new JsonArray())
			.put("@odata.deltaLink", server.baseUrl() + "/drives/d/root/delta?token=abc123").encode());

		assertThat(store().startDeltaToken("drive-1")).isEqualTo("abc123");
	}

	@Test
	public void testDeltaFollowsPagesAndReportsRemovals() throws IOException {
		server.enqueueJson(new JsonObject().put("value", new JsonArray()
			.add(item("f1", "a.mp4", 1L, false)))
			.put("@odata.nextLink", server.baseUrl() + "/next").encode());
		server.enqueueJson(new JsonObject().put("value", new JsonArray()
			.add(item("f2", "b.mp4", 1L, false).put("deleted", new JsonObject())))
			.put("@odata.deltaLink", server.baseUrl() + "/d?token=next-cursor").encode());

		CloudDelta delta = store().delta("drive-1", "cursor", false);

		assertThat(delta.changes()).hasSize(2);
		assertThat(delta.changes().get(0).removed()).isFalse();
		assertThat(delta.changes().get(1).removed()).isTrue();
		assertThat(delta.nextToken()).isEqualTo("next-cursor");
	}

	@Test
	public void testGoneIsReportedAsTokenExpired() throws IOException {
		server.fallback(Response.error(410,
			new JsonObject().put("error", new JsonObject().put("code", "resyncRequired")).encode()));

		assertThat(store().delta("drive-1", "stale", false).tokenExpired()).isTrue();
	}

	@Test
	public void testAppOnlyWithoutADriveIdFailsNamingTheFlag() {
		// There is no /me with client credentials, so guessing is not an option; the message has to
		// name what to set.
		assertThatThrownBy(() -> store().resolveDriveId(null))
			.isInstanceOf(IOException.class)
			.hasMessageContaining("--onedrive-default-drive-id");
	}

	@Test
	public void testDownloadPrefersThePreAuthenticatedUrl() throws IOException {
		server.enqueueJson(new JsonObject().put("id", "f1")
			.put(GraphJson.DOWNLOAD_URL, server.baseUrl() + "/preauth").encode());
		server.enqueue(Response.ok("bytes"));

		Path target = tempDir.resolve("out.mp4");
		CloudFileRef ref = new CloudFileRef(CloudProviderId.ONEDRIVE, "drive-1", "f1", "a.mp4", null,
			"video/mp4", "ctag:x", 5, 0, false, false, null, true);
		store().download(ref, target);

		assertThat(Files.readString(target)).isEqualTo("bytes");
		assertThat(server.lastRequest().path()).isEqualTo("/preauth");
	}

	@Test
	public void testDownloadFallsBackToTheContentEndpoint() throws IOException {
		// Some tenants do not surface the annotation; /content still works.
		server.enqueueJson("{\"id\":\"f1\"}");
		server.enqueue(Response.ok("bytes"));

		CloudFileRef ref = new CloudFileRef(CloudProviderId.ONEDRIVE, "drive-1", "f1", "a.mp4", null,
			"video/mp4", "ctag:x", 5, 0, false, false, null, true);
		store().download(ref, tempDir.resolve("out.mp4"));

		assertThat(server.lastRequest().path()).isEqualTo("/drives/drive-1/items/f1/content");
	}

	@Test
	public void testGetReturnsNullForAMissingItem() throws IOException {
		server.fallback(Response.error(404, "{\"error\":{\"code\":\"itemNotFound\"}}"));
		assertThat(store().get("drive-1", "nope")).isNull();
	}

	@Test
	public void testTokenExtractionHandlesAMissingOrDecoratedLink() {
		assertThat(GraphFileStore.tokenFromDeltaLink(null)).isNull();
		assertThat(GraphFileStore.tokenFromDeltaLink("https://x/delta")).isNull();
		assertThat(GraphFileStore.tokenFromDeltaLink("https://x/delta?token=abc&$top=5")).isEqualTo("abc");
	}
}
