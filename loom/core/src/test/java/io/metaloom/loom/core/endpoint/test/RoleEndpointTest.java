package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.sort.LoomSortKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.error.LoomHttpClientException;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RoleResponse;
import io.metaloom.loom.rest.model.role.RoleUpdateRequest;

public class RoleEndpointTest extends AbstractCRUDEndpointTest {

	@Test
	public void testSortByName() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			for (int i = 0; i < 100; i++) {
				RoleCreateRequest request = new RoleCreateRequest();
				request.setName("role_" + i);
				client.createRole(request).sync();
			}

			RoleListResponse pageResponse = client.listRoles()
				.addLimit(10)
				.sortBy(LoomSortKey.NAME)
				.sortDirection(SortDirection.ASCENDING)
				.sync();

			for (RoleResponse element : pageResponse.getData()) {
				System.out.println(element.getName());
			}
		}
	}

	@Test
	public void testBogusSortByKey() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			LoomHttpClientException ex = Assertions.assertThrows(LoomHttpClientException.class, () ->  {
				client.listRoles()
				.addLimit(10)
				.sortBy(LoomSortKey.EMAIL)
				.sortDirection(SortDirection.ASCENDING)
				.sync();
			});
			assertEquals(400, ex.getStatusCode());
			assertEquals("Bad Request", ex.getStatusMsg());
			assertEquals("Internal Server Error Field for sortkey email not found for type Roles", ex.getResponse().getMessage());
		}
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		RoleResponse role = client.loadRole(ROLE_UUID).sync();
		assertThat(role).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		RoleCreateRequest request = new RoleCreateRequest();
		request.setName("dummy name");
		RoleResponse role = client.createRole(request).sync();
		assertThat(role).isValid();

		RoleResponse role2 = client.loadRole(role.getUuid()).sync();
		assertThat(role).matches(role2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteRole(ROLE_UUID).sync();
		expect(404, "Not Found", client.loadRole(ROLE_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		RoleUpdateRequest update = new RoleUpdateRequest();
		update.setName("updated-name");
		RoleResponse response = client.updateRole(ROLE_UUID, update).sync();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("dummy name " + i);
			client.createRole(request).sync();
		}
		RoleListResponse list = client.listRoles().sync();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}
}
