package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryCreateRequest;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryFilesystemInfo;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryListResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryResponse;
import io.metaloom.loom.rest.model.asset.binary.AssetBinaryUpdateRequest;

public class AssetBinaryEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		AssetBinaryResponse binary = client.loadBinary(ASSET_LOCATION_UUID).sync().body();
		assertThat(binary).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		AssetBinaryCreateRequest request = new AssetBinaryCreateRequest();
		request.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/dummy/path"));
		request.setLibraryUuid(LIBRARY_UUID);
		request.setAssetUuid(ASSET_UUID);
		AssetBinaryResponse binary = client.createBinary(request).sync().body();
		assertThat(binary).isValid();

		AssetBinaryResponse binary2 = client.loadBinary(binary.getUuid()).sync().body();
		assertThat(binary).matches(binary2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteBinary(ASSET_LOCATION_UUID).sync().body();
		expect(404, "Not Found", client.loadBinary(ASSET_LOCATION_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		AssetBinaryUpdateRequest update = new AssetBinaryUpdateRequest();
		update.setFilesystem(new AssetBinaryFilesystemInfo().setPath("updated-path"));
		AssetBinaryResponse response = client.updateBinary(ASSET_LOCATION_UUID, update).sync().body();
		assertThat(response).isValid().hasPath("updated-path");
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			AssetBinaryCreateRequest request = new AssetBinaryCreateRequest();
			request.setAssetUuid(ASSET_UUID);
			request.setLibraryUuid(LIBRARY_UUID);
			request.setFilesystem(new AssetBinaryFilesystemInfo().setPath("dummy path " + i));
			client.createBinary(request).sync().body();
		}
		AssetBinaryListResponse list = client.listBinaries().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		AssetBinaryCreateRequest request = new AssetBinaryCreateRequest();
		request.setFilesystem(new AssetBinaryFilesystemInfo().setPath("/dummy/path"));
		request.setLibraryUuid(LIBRARY_UUID);
		request.setAssetUuid(ASSET_UUID);
		return client.createBinary(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadBinary(ASSET_LOCATION_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listBinaries();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteBinary(ASSET_LOCATION_UUID);
	}

}
