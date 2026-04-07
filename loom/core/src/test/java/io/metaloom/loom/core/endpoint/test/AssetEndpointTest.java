package io.metaloom.loom.core.endpoint.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
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
import io.metaloom.utils.hash.SHA512;

public class AssetEndpointTest extends AbstractCRUDEndpointTest {

	// A unique SHA512 that does not collide with fixture data
	private static final SHA512 CREATE_SHA512 = SHA512.fromString(
		"aa000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001");

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

		AssetResponse response = client.createAsset(request).sync();
		Assertions.assertThat(response).matches(request);
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		AssetResponse response = client.loadAsset(ASSET_UUID).sync();
		assertNotNull(response);
	}

	@Test
	public void testLoadBySHA512() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// The test fixture creates an asset with SHA512SUM — load it via the /sha512/ sub-path
			AssetResponse response = client.loadAsset(SHA512SUM).sync();
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
		AssetResponse created = client.createAsset(createReq).sync();
		assertNotNull(created.getUuid());

		client.deleteAsset(created.getUuid()).sync();
		expect(404, "Not Found", client.loadAsset(created.getUuid()));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		final String NEW_NAME = "the_new_local_path.jpg";
		AssetUpdateRequest request = new AssetUpdateRequest();
		request.setFile(new FileInfo().setFilename(NEW_NAME));
		AssetResponse response = client.updateAsset(ASSET_UUID, request).sync();
		assertEquals(NEW_NAME, response.getFile().getFilename());

		AssetResponse loadResponse = client.loadAsset(ASSET_UUID).sync();
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
			client.createAsset(request).sync();
		}

		AssetListResponse response = client.listAssets().sync();
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

		AssetBulkResponse response = client.bulkCreateAssets(bulkRequest).sync();
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
		client.bulkCreateAssets(createRequest).sync();

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

		AssetBulkResponse response = client.bulkUpdateAssets(updateRequest).sync();
		assertNotNull(response);
		assertEquals(3, response.getTotal());
		assertEquals(3, response.getCreated()); // 'created' field is reused for successful count
		assertEquals(0, response.getFailed());
	}

}