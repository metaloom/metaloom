package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.group.GroupCreateRequest;
import io.metaloom.loom.rest.model.group.GroupListResponse;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.group.GroupUpdateRequest;

public class GroupEndpointTest extends AbstractCRUDEndpointTest {

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		GroupResponse group = client.loadGroup(GROUP_UUID).sync();
		assertThat(group).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		GroupCreateRequest request = new GroupCreateRequest();
		request.setName("dummy name");
		GroupResponse group = client.createGroup(request).sync();
		assertThat(group).isValid();

		GroupResponse group2 = client.loadGroup(group.getUuid()).sync();
		assertThat(group).matches(group2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteGroup(GROUP_UUID).sync();
		expect(404, "Not Found", client.loadGroup(GROUP_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		GroupUpdateRequest update = new GroupUpdateRequest();
		update.setName("updated-name");
		GroupResponse response = client.updateGroup(GROUP_UUID, update).sync();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			GroupCreateRequest request = new GroupCreateRequest();
			request.setName("dummy name " + i);
			client.createGroup(request).sync();
		}
		GroupListResponse list = client.listGroups().sync();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

}
