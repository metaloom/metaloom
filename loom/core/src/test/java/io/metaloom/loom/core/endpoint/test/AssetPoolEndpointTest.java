package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.pool.AssetPoolCreateRequest;
import io.metaloom.loom.rest.model.pool.AssetPoolListResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolResponse;
import io.metaloom.loom.rest.model.pool.AssetPoolUpdateRequest;

public class AssetPoolEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		AssetPoolResponse pool = client.loadPool(ASSET_POOL_UUID).sync().body();
		assertThat(pool).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		AssetPoolCreateRequest request = new AssetPoolCreateRequest();
		request.setName("test-pool");
		request.setFsPath("/tank/test/binaries");
		AssetPoolResponse pool = client.createPool(request).sync().body();
		assertThat(pool).isValid().hasName("test-pool");

		AssetPoolResponse pool2 = client.loadPool(pool.getUuid()).sync().body();
		assertThat(pool).matches(pool2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deletePool(ASSET_POOL_UUID).sync().body();
		expect(404, "Not Found", client.loadPool(ASSET_POOL_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		AssetPoolUpdateRequest update = new AssetPoolUpdateRequest();
		update.setName("updated-pool-name");
		AssetPoolResponse response = client.updatePool(ASSET_POOL_UUID, update).sync().body();
		assertThat(response).isValid().hasName("updated-pool-name");
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			AssetPoolCreateRequest request = new AssetPoolCreateRequest();
			request.setName("pool-" + i);
			request.setFsPath("/tank/pool/" + i);
			client.createPool(request).sync().body();
		}
		AssetPoolListResponse list = client.listPools().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		AssetPoolCreateRequest request = new AssetPoolCreateRequest();
		request.setName("perm-check");
		request.setFsPath("/tank/test/binaries");
		return client.createPool(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadPool(ASSET_POOL_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listPools();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deletePool(ASSET_POOL_UUID);
	}

}
