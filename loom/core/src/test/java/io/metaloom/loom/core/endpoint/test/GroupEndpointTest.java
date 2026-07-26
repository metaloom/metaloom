package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.core.endpoint.ReplaceEndpointTestcases;
import io.metaloom.loom.rest.model.group.GroupCreateRequest;
import io.metaloom.loom.rest.model.group.GroupListResponse;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.group.GroupUpdateRequest;
import io.vertx.core.json.JsonObject;

public class GroupEndpointTest extends AbstractCRUDEndpointTest implements ReplaceEndpointTestcases {

	@Test
	@Override
	public void testPatch() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			GroupUpdateRequest update = new GroupUpdateRequest();
			update.setName("patched-name");
			GroupResponse response = client.patchGroup(GROUP_UUID, update).sync().body();
			assertThat(response).isValid();
			assertEquals("patched-name", client.loadGroup(GROUP_UUID).sync().body().getName());
		}
	}

	@Test
	@Override
	public void testReplace() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			GroupUpdateRequest update = new GroupUpdateRequest();
			update.setName("replaced-name");
			update.setMeta(new JsonObject());
			GroupResponse response = client.replaceGroup(GROUP_UUID, update).sync().body();
			assertThat(response).isValid();
			assertEquals("replaced-name", client.loadGroup(GROUP_UUID).sync().body().getName());
		}
	}

	@Test
	@Override
	public void testReplaceRejectsPartialBody() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			// The meta field is missing
			GroupUpdateRequest update = new GroupUpdateRequest();
			update.setName("only-name");
			expect(400, "Bad Request", client.replaceGroup(GROUP_UUID, update));
		}
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		GroupResponse group = client.loadGroup(GROUP_UUID).sync().body();
		assertThat(group).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		GroupCreateRequest request = new GroupCreateRequest();
		request.setName("dummy name");
		GroupResponse group = client.createGroup(request).sync().body();
		assertThat(group).isValid();

		GroupResponse group2 = client.loadGroup(group.getUuid()).sync().body();
		assertThat(group).matches(group2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteGroup(GROUP_UUID).sync().body();
		expect(404, "Not Found", client.loadGroup(GROUP_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		GroupUpdateRequest update = new GroupUpdateRequest();
		update.setName("updated-name");
		GroupResponse response = client.updateGroup(GROUP_UUID, update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			GroupCreateRequest request = new GroupCreateRequest();
			request.setName("dummy name " + i);
			client.createGroup(request).sync().body();
		}
		GroupListResponse list = client.listGroups().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		GroupCreateRequest request = new GroupCreateRequest();
		request.setName("perm-check");
		return client.createGroup(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadGroup(GROUP_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listGroups();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteGroup(GROUP_UUID);
	}

}
