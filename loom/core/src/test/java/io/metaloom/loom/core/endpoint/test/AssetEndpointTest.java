package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.reaction.ReactionType;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.core.endpoint.ReplaceEndpointTestcases;
import io.metaloom.loom.rest.model.assertj.Assertions;
import io.metaloom.loom.rest.model.asset.AssetBulkCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetBulkItemResponse;
import io.metaloom.loom.rest.model.asset.AssetBulkItemResponse.BulkItemStatus;
import io.metaloom.loom.rest.model.asset.AssetBulkResponse;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateEntry;
import io.metaloom.loom.rest.model.asset.AssetBulkUpdateRequest;
import io.metaloom.loom.rest.model.asset.AssetCreateRequest;
import io.metaloom.loom.rest.model.asset.AssetListResponse;
import io.metaloom.loom.rest.model.asset.AssetResponse;
import io.metaloom.loom.rest.model.asset.AssetUpdateRequest;
import io.metaloom.loom.rest.model.asset.info.AudioInfo;
import io.metaloom.loom.rest.model.asset.info.DocumentInfo;
import io.metaloom.loom.rest.model.asset.info.FileInfo;
import io.metaloom.loom.rest.model.asset.info.GeoLocationInfo;
import io.metaloom.loom.rest.model.asset.info.HashInfo;
import io.metaloom.loom.rest.model.asset.info.ImageInfo;
import io.metaloom.loom.rest.model.asset.info.MediaInfo;
import io.metaloom.loom.rest.model.asset.info.VideoInfo;
import io.metaloom.loom.rest.model.comment.CommentCreateRequest;
import io.metaloom.loom.rest.model.comment.CommentResponse;
import io.metaloom.loom.rest.model.reaction.ReactionCreateRequest;
import io.metaloom.loom.rest.model.reaction.ReactionResponse;
import io.metaloom.utils.hash.SHA512;
import io.vertx.core.json.JsonObject;

public class AssetEndpointTest extends AbstractCRUDEndpointTest implements ReplaceEndpointTestcases {

	// A unique SHA512 that does not collide with fixture data
	private static final SHA512 CREATE_SHA512 = SHA512.fromString(
		"aa000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001");

	/**
	 * Build an update request which carries every replaceable field of the asset model. Kind specific fields are annotated with {@code @ReplaceOptional}
	 * and are thus not required for a full replace.
	 */
	private AssetUpdateRequest fullUpdateRequest(String filename) {
		AssetUpdateRequest request = new AssetUpdateRequest();
		request.setMeta(new JsonObject());
		request.setTags(new ArrayList<>());
		request.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename(filename).setSize(1024L).setOrigin(INITIAL_ORIGIN));
		request.setHashes(new HashInfo().setSHA512(SHA512SUM));
		request.setMedia(new MediaInfo().setWidth(800).setHeight(600));
		return request;
	}

	@Test
	@Override
	public void testPatch() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			final String NEW_NAME = "patched.jpg";
			AssetUpdateRequest request = new AssetUpdateRequest();
			request.setFile(new FileInfo().setFilename(NEW_NAME));
			AssetResponse response = client.patchAsset(ASSET_UUID, request).sync().body();
			assertEquals(NEW_NAME, response.getFile().getFilename());
			assertEquals(NEW_NAME, client.loadAsset(ASSET_UUID).sync().body().getFile().getFilename());
		}
	}

	@Test
	@Override
	public void testReplace() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			final String NEW_NAME = "replaced.jpg";
			AssetResponse response = client.replaceAsset(ASSET_UUID, fullUpdateRequest(NEW_NAME)).sync().body();
			assertEquals(NEW_NAME, response.getFile().getFilename());
			assertEquals(NEW_NAME, client.loadAsset(ASSET_UUID).sync().body().getFile().getFilename());
		}
	}

	@Test
	@Override
	public void testReplaceRejectsPartialBody() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			AssetUpdateRequest request = new AssetUpdateRequest();
			request.setFile(new FileInfo().setFilename("only_a_file.jpg"));
			expect(400, "Bad Request", client.replaceAsset(ASSET_UUID, request));
		}
	}

	/**
	 * The SHA-512 routes use a literal path prefix which must be registered before the :uuid wildcard. This asserts the wildcard does not swallow them.
	 */
	@Test
	public void testReplaceBySHA512() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			final String NEW_NAME = "replaced_by_hash.jpg";
			AssetResponse response = client.replaceAsset(SHA512SUM, fullUpdateRequest(NEW_NAME)).sync().body();
			assertEquals(NEW_NAME, response.getFile().getFilename());
			assertEquals(ASSET_UUID, response.getUuid());
		}
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		AssetCreateRequest request = new AssetCreateRequest();
		request.setMeta(meta());

		FileInfo fileInfo = new FileInfo();
		fileInfo.setMimeType(IMAGE_MIMETYPE);
		fileInfo.setFilename("test.png");
		fileInfo.setSize(42L * 1024);
		fileInfo.setOrigin(INITIAL_ORIGIN);
		request.setFile(fileInfo);

		HashInfo hashes = new HashInfo();
		hashes.setSHA256(SHA256SUM);
		hashes.setMD5(MD5SUM);
		hashes.setSHA512(CREATE_SHA512);
		request.setHashes(hashes);

		MediaInfo mediaInfo = new MediaInfo();
		mediaInfo.setDuration(242L);
		mediaInfo.setWidth(800);
		mediaInfo.setHeight(600);
		request.setMedia(mediaInfo);

		AudioInfo audioInfo = new AudioInfo();
		audioInfo.setBpm(140);
		audioInfo.setBitrate(320 * 1024);
		audioInfo.setChannels(2);
		audioInfo.setEncoding("FLAC");
		audioInfo.setSamplingRate(44100);
		request.setAudio(audioInfo);

		VideoInfo videoInfo = new VideoInfo();
		videoInfo.setBitrate(40_000);
		videoInfo.setEncoding("H265");
		request.setVideo(videoInfo);

		DocumentInfo docInfo = new DocumentInfo();
		docInfo.setWordCount(42L);
		request.setDocument(docInfo);

		ImageInfo imageInfo = new ImageInfo();
		imageInfo.setDominantColor(DOMINANT_COLOR);
		request.setImage(imageInfo);

		GeoLocationInfo geoInfo = new GeoLocationInfo();
		geoInfo.setAlias("Zoo");
		geoInfo.setLat(42.0);
		geoInfo.setLon(41.0);
		request.setGeo(geoInfo);

		AssetResponse response = client.createAsset(request).sync().body();
		Assertions.assertThat(response).matches(request);
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		AssetResponse response = client.loadAsset(ASSET_UUID).sync().body();
		assertNotNull(response);
	}

	@Test
	public void testLoadBySHA512() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// The test fixture creates an asset with SHA512SUM — load it via the /sha512/ sub-path
			AssetResponse response = client.loadAsset(SHA512SUM).sync().body();
			assertNotNull(response);
			assertEquals(ASSET_UUID, response.getUuid());
		}
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		// Create a standalone asset with no FK references so it can be cleanly deleted
		SHA512 deleteSha = SHA512.fromString(
			"bb000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001");
		AssetCreateRequest createReq = new AssetCreateRequest();
		createReq.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("to_delete.png").setSize(1024L).setOrigin(INITIAL_ORIGIN));
		createReq.setHashes(new HashInfo().setSHA512(deleteSha));
		AssetResponse created = client.createAsset(createReq).sync().body();
		assertNotNull(created.getUuid());

		client.deleteAsset(created.getUuid()).sync().body();
		expect(404, "Not Found", client.loadAsset(created.getUuid()));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		final String NEW_NAME = "the_new_local_path.jpg";
		AssetUpdateRequest request = new AssetUpdateRequest();
		request.setFile(new FileInfo().setFilename(NEW_NAME));
		AssetResponse response = client.updateAsset(ASSET_UUID, request).sync().body();
		assertEquals(NEW_NAME, response.getFile().getFilename());

		AssetResponse loadResponse = client.loadAsset(ASSET_UUID).sync().body();
		assertEquals(NEW_NAME, loadResponse.getFile().getFilename());
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			AssetCreateRequest request = new AssetCreateRequest();

			FileInfo fileInfo = new FileInfo();
			fileInfo.setFilename("test_" + i + ".png");
			fileInfo.setMimeType(IMAGE_MIMETYPE);
			fileInfo.setSize(42L * 1024);
			fileInfo.setOrigin(INITIAL_ORIGIN);

			request.setFile(fileInfo);
			// Replace the last 4 hex chars to produce unique but valid 128-char hashes
			String base = SHA512SUM_3.toString().substring(0, 124);
			String suffix = String.format("%04x", i);
			request.setHashes(new HashInfo().setSHA512(SHA512.fromString(base + suffix)));
			client.createAsset(request).sync().body();
		}

		AssetListResponse response = client.listAssets().sync().body();
		assertNotNull(response);
	}

	@Test
	public void testBulkCreate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			testBulkCreate(client);
		}
	}

	protected void testBulkCreate(LoomHttpClient client) throws LoomClientException {
		AssetBulkCreateRequest bulkRequest = new AssetBulkCreateRequest();

		// Add 5 assets to the bulk request
		for (int i = 0; i < 5; i++) {
			AssetCreateRequest request = new AssetCreateRequest();

			FileInfo fileInfo = new FileInfo();
			fileInfo.setMimeType(IMAGE_MIMETYPE);
			fileInfo.setFilename("bulk_test_" + i + ".png");
			fileInfo.setSize(42L * 1024);
			fileInfo.setOrigin(INITIAL_ORIGIN);
			request.setFile(fileInfo);

			HashInfo hashes = new HashInfo();
			hashes.setSHA512(SHA512.fromString(SHA512SUM.toString().substring(0, SHA512SUM.toString().length() - 1) + i));
			request.setHashes(hashes);

			bulkRequest.add(request);
		}

		AssetBulkResponse response = client.bulkCreateAssets(bulkRequest).sync().body();
		assertNotNull(response);
		assertEquals(5, response.getTotal());
		assertEquals(5, response.getCreated());
		assertEquals(0, response.getFailed());
		assertEquals(5, response.getItems().size());

		for (AssetBulkItemResponse item : response.getItems()) {
			assertEquals(BulkItemStatus.CREATED, item.getStatus());
		}
	}

	@Test
	public void testBulkUpdate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			testBulkUpdate(client);
		}
	}

	protected void testBulkUpdate(LoomHttpClient client) throws LoomClientException {
		// First, create some assets to update
		AssetBulkCreateRequest createRequest = new AssetBulkCreateRequest();
		SHA512[] hashes = new SHA512[3];
		for (int i = 0; i < 3; i++) {
			AssetCreateRequest request = new AssetCreateRequest();

			FileInfo fileInfo = new FileInfo();
			fileInfo.setMimeType(IMAGE_MIMETYPE);
			fileInfo.setFilename("bulk_update_" + i + ".png");
			fileInfo.setSize(42L * 1024);
			fileInfo.setOrigin(INITIAL_ORIGIN);
			request.setFile(fileInfo);

			hashes[i] = SHA512.fromString(SHA512SUM_2.toString().substring(0, SHA512SUM_2.toString().length() - 1) + i);
			request.setHashes(new HashInfo().setSHA512(hashes[i]));
			createRequest.add(request);
		}
		client.bulkCreateAssets(createRequest).sync().body();

		// Now bulk update them
		AssetBulkUpdateRequest updateRequest = new AssetBulkUpdateRequest();
		for (int i = 0; i < 3; i++) {
			AssetBulkUpdateEntry entry = new AssetBulkUpdateEntry();
			entry.setHashes(new HashInfo().setSHA512(hashes[i]));
			AssetUpdateRequest update = new AssetUpdateRequest();
			update.setFile(new FileInfo().setFilename("updated_" + i + ".png"));
			entry.setUpdate(update);
			updateRequest.add(entry);
		}

		AssetBulkResponse response = client.bulkUpdateAssets(updateRequest).sync().body();
		assertNotNull(response);
		assertEquals(3, response.getTotal());
		assertEquals(3, response.getCreated()); // 'created' field is reused for successful count
		assertEquals(0, response.getFailed());
	}

	/**
	 * Deleting an asset that is referenced by a task and carries a reaction succeeds, and takes only the asset's own things with it (V2.73, V2.74).
	 *
	 * <p>
	 * {@code asset_task} and {@code reaction.asset_uuid} were plain foreign keys, so the delete failed on a foreign-key violation and the route
	 * answered 500. Both cascade now. What must not move is anything anchored somewhere other than this asset: the task itself - which may be about
	 * several assets - along with the comment and the reaction written on the task, and the fixture asset's own reaction.
	 * </p>
	 */
	@Test
	public void testDeleteAssetReferencedByTask() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			SHA512 sha = SHA512.fromString(
				"dd000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001");
			AssetCreateRequest request = new AssetCreateRequest();
			request.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("tasked.png").setSize(100L).setOrigin(INITIAL_ORIGIN));
			request.setHashes(new HashInfo().setSHA512(sha));
			AssetResponse asset = client.createAsset(request).sync().body();

			client.assignTaskToAsset(asset.getUuid(), TASK_UUID).sync().body();
			assertEquals(1, client.listAssetTasks(asset.getUuid()).sync().body().getData().size(), "The task is linked before the delete");

			ReactionCreateRequest reactionRequest = new ReactionCreateRequest();
			reactionRequest.setRating(42);
			reactionRequest.setType(ReactionType.PLUS_ONE);
			ReactionResponse assetReaction = client.createAssetReaction(asset.getUuid(), reactionRequest).sync().body();

			// A workflow star rating is a reaction too, on its own type - so it must cascade the same way.
			ReactionCreateRequest ratingRequest = new ReactionCreateRequest();
			ratingRequest.setType(ReactionType.RATING);
			ratingRequest.setRating(9);
			ReactionResponse assetRating = client.createAssetReaction(asset.getUuid(), ratingRequest).sync().body();

			// Bystanders: social content on the task, and a reaction on a different asset.
			CommentCreateRequest commentRequest = new CommentCreateRequest();
			commentRequest.setTitle("Task feedback");
			commentRequest.setText("Nothing to do with the asset");
			CommentResponse taskComment = client.createTaskComment(TASK_UUID, commentRequest).sync().body();
			ReactionResponse taskReaction = client.createTaskReaction(TASK_UUID, reactionRequest).sync().body();
			ReactionResponse otherAssetReaction = client.createAssetReaction(ASSET_UUID, reactionRequest).sync().body();

			client.deleteAsset(asset.getUuid()).sync().body();

			expect(404, "Not Found", client.loadAsset(asset.getUuid()));
			expect(404, "Not Found", client.loadAssetReaction(asset.getUuid(), assetReaction.getUuid()));
			expect(404, "Not Found", client.loadAssetReaction(asset.getUuid(), assetRating.getUuid()));

			assertNotNull(client.loadTask(TASK_UUID).sync().body(), "The task must survive the deletion of an asset it referenced");
			assertNotNull(client.loadComment(taskComment.getUuid()).sync().body(), "A comment on the task is not about the asset");
			assertNotNull(client.loadTaskReaction(TASK_UUID, taskReaction.getUuid()).sync().body(), "Nor is a reaction on the task");
			assertNotNull(client.loadAssetReaction(ASSET_UUID, otherAssetReaction.getUuid()).sync().body(),
				"A reaction on another asset must be untouched");
		}
	}

	// --- Meta handling tests ---

	@Test
	public void testCreateWithMeta() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			SHA512 sha = SHA512.fromString(
				"cc000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001");
			AssetCreateRequest request = new AssetCreateRequest();
			request.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("meta_create.png").setSize(100L).setOrigin(INITIAL_ORIGIN));
			request.setHashes(new HashInfo().setSHA512(sha));
			request.setMeta(new JsonObject().put("rating", 5).put("source", "upload"));

			AssetResponse response = client.createAsset(request).sync().body();
			assertNotNull(response.getMeta());
			assertEquals(5, response.getMeta().getInteger("rating"));
			assertEquals("upload", response.getMeta().getString("source"));

			// Reload and verify persistence
			AssetResponse loaded = client.loadAsset(response.getUuid()).sync().body();
			assertNotNull(loaded.getMeta());
			assertEquals(5, loaded.getMeta().getInteger("rating"));
			assertEquals("upload", loaded.getMeta().getString("source"));
		}
	}

	@Test
	public void testSetMetaViaUpdate() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// The fixture asset has no meta set — add meta via update
			AssetUpdateRequest request = new AssetUpdateRequest();
			request.setMeta(new JsonObject().put("category", "nature").put("priority", 3));
			AssetResponse response = client.updateAsset(ASSET_UUID, request).sync().body();

			assertNotNull(response.getMeta());
			assertEquals("nature", response.getMeta().getString("category"));
			assertEquals(3, response.getMeta().getInteger("priority"));

			// Verify via reload
			AssetResponse loaded = client.loadAsset(ASSET_UUID).sync().body();
			assertNotNull(loaded.getMeta());
			assertEquals("nature", loaded.getMeta().getString("category"));
			assertEquals(3, loaded.getMeta().getInteger("priority"));
		}
	}

	@Test
	public void testUpdateMetaReplacesExistingMeta() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// First, create an asset with initial meta
			SHA512 sha = SHA512.fromString(
				"cc000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002");
			AssetCreateRequest createReq = new AssetCreateRequest();
			createReq.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("meta_update.png").setSize(200L).setOrigin(INITIAL_ORIGIN));
			createReq.setHashes(new HashInfo().setSHA512(sha));
			createReq.setMeta(new JsonObject().put("version", 1).put("oldKey", "oldValue"));
			AssetResponse created = client.createAsset(createReq).sync().body();

			// Now update meta with completely new content
			AssetUpdateRequest updateReq = new AssetUpdateRequest();
			updateReq.setMeta(new JsonObject().put("version", 2).put("newKey", "newValue"));
			AssetResponse updated = client.updateAsset(created.getUuid(), updateReq).sync().body();

			assertNotNull(updated.getMeta());
			assertEquals(2, updated.getMeta().getInteger("version"));
			assertEquals("newValue", updated.getMeta().getString("newKey"));
			// The old key should no longer be present — meta is replaced, not merged
			assertNull(updated.getMeta().getString("oldKey"));
		}
	}

	@Test
	public void testCreateWithoutMeta() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			SHA512 sha = SHA512.fromString(
				"cc000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000003");
			AssetCreateRequest request = new AssetCreateRequest();
			request.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("no_meta.png").setSize(50L).setOrigin(INITIAL_ORIGIN));
			request.setHashes(new HashInfo().setSHA512(sha));
			// No meta set

			AssetResponse response = client.createAsset(request).sync().body();
			assertNull(response.getMeta());
		}
	}

	@Test
	public void testUpdateMetaWithNestedObject() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			SHA512 sha = SHA512.fromString(
				"cc000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000004");
			AssetCreateRequest createReq = new AssetCreateRequest();
			createReq.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("nested_meta.png").setSize(300L).setOrigin(INITIAL_ORIGIN));
			createReq.setHashes(new HashInfo().setSHA512(sha));
			AssetResponse created = client.createAsset(createReq).sync().body();

			// Set meta with a nested JSON object
			JsonObject nestedMeta = new JsonObject()
				.put("tags", new JsonObject().put("genre", "wildlife").put("mood", "serene"))
				.put("score", 9.5);
			AssetUpdateRequest updateReq = new AssetUpdateRequest();
			updateReq.setMeta(nestedMeta);
			AssetResponse updated = client.updateAsset(created.getUuid(), updateReq).sync().body();

			assertNotNull(updated.getMeta());
			assertEquals("wildlife", updated.getMeta().getJsonObject("tags").getString("genre"));
			assertEquals("serene", updated.getMeta().getJsonObject("tags").getString("mood"));
			assertEquals(9.5, updated.getMeta().getDouble("score"), 0.001);

			// Verify persistence via reload
			AssetResponse loaded = client.loadAsset(created.getUuid()).sync().body();
			assertNotNull(loaded.getMeta());
			assertEquals("wildlife", loaded.getMeta().getJsonObject("tags").getString("genre"));
		}
	}

	@Test
	public void testRemoveMetaViaEmptyObject() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// Create asset with meta
			SHA512 sha = SHA512.fromString(
				"cc000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000005");
			AssetCreateRequest createReq = new AssetCreateRequest();
			createReq.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("clear_meta.png").setSize(400L).setOrigin(INITIAL_ORIGIN));
			createReq.setHashes(new HashInfo().setSHA512(sha));
			createReq.setMeta(new JsonObject().put("toRemove", "data"));
			AssetResponse created = client.createAsset(createReq).sync().body();
			assertNotNull(created.getMeta());

			// Replace meta with empty object to effectively clear it
			AssetUpdateRequest updateReq = new AssetUpdateRequest();
			updateReq.setMeta(new JsonObject());
			AssetResponse updated = client.updateAsset(created.getUuid(), updateReq).sync().body();

			assertNotNull(updated.getMeta());
			assertTrue(updated.getMeta().isEmpty());
		}
	}

	@Test
	public void testUpdateDoesNotClearMetaWhenNotProvided() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// Create asset with meta
			SHA512 sha = SHA512.fromString(
				"cc000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000006");
			AssetCreateRequest createReq = new AssetCreateRequest();
			createReq.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("keep_meta.png").setSize(500L).setOrigin(INITIAL_ORIGIN));
			createReq.setHashes(new HashInfo().setSHA512(sha));
			createReq.setMeta(new JsonObject().put("keep", "this"));
			AssetResponse created = client.createAsset(createReq).sync().body();

			// Update only filename — meta should remain untouched
			AssetUpdateRequest updateReq = new AssetUpdateRequest();
			updateReq.setFile(new FileInfo().setFilename("renamed.png"));
			AssetResponse updated = client.updateAsset(created.getUuid(), updateReq).sync().body();

			assertNotNull(updated.getMeta());
			assertEquals("this", updated.getMeta().getString("keep"));
			assertEquals("renamed.png", updated.getFile().getFilename());
		}
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		AssetCreateRequest request = new AssetCreateRequest();
		request.setFile(new FileInfo().setMimeType(IMAGE_MIMETYPE).setFilename("perm-check.png").setSize(500L).setOrigin(INITIAL_ORIGIN));
		request.setHashes(new HashInfo().setSHA512(CREATE_SHA512));
		return client.createAsset(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadAsset(ASSET_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listAssets();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteAsset(ASSET_UUID);
	}

}