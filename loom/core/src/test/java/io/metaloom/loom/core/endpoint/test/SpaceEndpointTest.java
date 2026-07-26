package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.space.SpaceCreateRequest;
import io.metaloom.loom.rest.model.space.SpaceListResponse;
import io.metaloom.loom.rest.model.space.SpaceResponse;
import io.metaloom.loom.rest.model.space.SpaceUpdateRequest;

public class SpaceEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		SpaceResponse space = client.loadSpace(PROJECT_UUID).sync().body();
		assertThat(space).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		SpaceCreateRequest request = new SpaceCreateRequest();
		request.setName("dummy name");
		SpaceResponse space = client.createSpace(request).sync().body();
		assertThat(space).isValid();

		SpaceResponse project2 = client.loadSpace(space.getUuid()).sync().body();
		assertThat(space).matches(project2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteSpace(PROJECT_UUID).sync().body();
		expect(404, "Not Found", client.loadSpace(PROJECT_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		SpaceUpdateRequest update = new SpaceUpdateRequest();
		update.setName("updated-name");
		SpaceResponse response = client.updateSpace(PROJECT_UUID, update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			SpaceCreateRequest request = new SpaceCreateRequest();
			request.setName("dummy name");
			client.createSpace(request).sync().body();
		}
		SpaceListResponse list = client.listSpaces().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		SpaceCreateRequest request = new SpaceCreateRequest();
		request.setName("perm-check");
		return client.createSpace(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadSpace(PROJECT_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listSpaces();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteSpace(PROJECT_UUID);
	}

}
