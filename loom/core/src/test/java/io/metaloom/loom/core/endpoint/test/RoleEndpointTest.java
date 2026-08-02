package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.sort.LoomSortKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.client.http.error.LoomHttpClientException;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.role.RoleCreateRequest;
import io.metaloom.loom.rest.model.role.RoleListResponse;
import io.metaloom.loom.rest.model.role.RolePermission;
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
				client.createRole(request).sync().body();
			}

			RoleListResponse pageResponse = client.listRoles()
				.addLimit(10)
				.sortBy(LoomSortKey.NAME)
				.sortDirection(SortDirection.ASCENDING)
				.sync().body();

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
				.sync().body();
			});
			assertEquals(400, ex.getStatusCode());
			assertEquals("Bad Request", ex.getStatusMsg());
			assertEquals("Unknown sort field email for Roles", ex.getResponse().getMessage());
		}
	}

	/**
	 * The permission list on a create request is load-bearing: it is persisted to {@code role_permission} and comes back on the response and on every
	 * subsequent load. Until this was wired up the field was parsed, validated and then dropped.
	 */
	@Test
	public void testCreateWithPermissions() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("role-with-perms");
			request.setPermissions(List.of(RolePermission.READ_ASSET, RolePermission.CREATE_ASSET));
			RoleResponse created = client.createRole(request).sync().body();

			assertEquals(List.of(RolePermission.CREATE_ASSET, RolePermission.READ_ASSET), created.getPermissions(),
				"The create response must report the granted permissions, sorted by name");

			RoleResponse loaded = client.loadRole(created.getUuid()).sync().body();
			assertEquals(List.of(RolePermission.CREATE_ASSET, RolePermission.READ_ASSET), loaded.getPermissions(),
				"A subsequent load must report the same permissions - proving they were persisted, not echoed");
		}
	}

	/**
	 * A role created without a permission list grants nothing, and reports that as an empty list rather than as an absent field.
	 */
	@Test
	public void testCreateWithoutPermissions() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("role-without-perms");
			RoleResponse created = client.createRole(request).sync().body();

			assertNotNull(created.getPermissions(), "The field must be present even when the role grants nothing");
			assertTrue(created.getPermissions().isEmpty(), "A role created without permissions grants nothing");
		}
	}

	/**
	 * An update replaces the permission set, it does not append to it. The admin ACL matrix always sends the full desired state, so unticking a box
	 * has to revoke.
	 */
	@Test
	public void testUpdateReplacesPermissions() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("role-replace-perms");
			request.setPermissions(List.of(RolePermission.READ_ASSET, RolePermission.CREATE_ASSET));
			RoleResponse created = client.createRole(request).sync().body();

			RoleUpdateRequest update = new RoleUpdateRequest();
			update.setPermissions(List.of(RolePermission.READ_TAG));
			RoleResponse updated = client.updateRole(created.getUuid(), update).sync().body();

			assertEquals(List.of(RolePermission.READ_TAG), updated.getPermissions(),
				"The new list must replace the old one rather than being appended to it");

			RoleResponse loaded = client.loadRole(created.getUuid()).sync().body();
			assertEquals(List.of(RolePermission.READ_TAG), loaded.getPermissions(), "The replacement must be persisted");
		}
	}

	/**
	 * {@code null} and {@code []} are different requests, and the difference is part of the endpoint contract: an update which says nothing about
	 * permissions must not silently strip the role - otherwise renaming a role would revoke every grant it carries.
	 */
	@Test
	public void testNullPermissionsLeaveThemUnchanged() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("role-null-perms");
			request.setPermissions(List.of(RolePermission.READ_ASSET));
			RoleResponse created = client.createRole(request).sync().body();

			// A rename only: the permissions field is absent from the request body.
			RoleUpdateRequest rename = new RoleUpdateRequest();
			rename.setName("role-null-perms-renamed");
			RoleResponse updated = client.updateRole(created.getUuid(), rename).sync().body();

			assertEquals("role-null-perms-renamed", updated.getName());
			assertEquals(List.of(RolePermission.READ_ASSET), updated.getPermissions(),
				"An absent permission list must leave the existing grants alone");
		}
	}

	/**
	 * The counterpart of the previous case: an explicit empty list is a revoke-all.
	 */
	@Test
	public void testEmptyPermissionsRevokeEverything() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("role-empty-perms");
			request.setPermissions(List.of(RolePermission.READ_ASSET, RolePermission.READ_TAG));
			RoleResponse created = client.createRole(request).sync().body();

			RoleUpdateRequest revoke = new RoleUpdateRequest();
			revoke.setPermissions(List.of());
			RoleResponse updated = client.updateRole(created.getUuid(), revoke).sync().body();

			assertTrue(updated.getPermissions().isEmpty(), "An explicit empty list must revoke every grant");

			RoleResponse loaded = client.loadRole(created.getUuid()).sync().body();
			assertTrue(loaded.getPermissions().isEmpty(), "The revocation must be persisted");
		}
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		RoleResponse role = client.loadRole(ROLE_UUID).sync().body();
		assertThat(role).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		RoleCreateRequest request = new RoleCreateRequest();
		request.setName("dummy name");
		RoleResponse role = client.createRole(request).sync().body();
		assertThat(role).isValid();

		RoleResponse role2 = client.loadRole(role.getUuid()).sync().body();
		assertThat(role).matches(role2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteRole(ROLE_UUID).sync().body();
		expect(404, "Not Found", client.loadRole(ROLE_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		RoleUpdateRequest update = new RoleUpdateRequest();
		update.setName("updated-name");
		RoleResponse response = client.updateRole(ROLE_UUID, update).sync().body();
		assertThat(response).isValid();
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			RoleCreateRequest request = new RoleCreateRequest();
			request.setName("dummy name " + i);
			client.createRole(request).sync().body();
		}
		RoleListResponse list = client.listRoles().sync().body();
		assertThat(list).isValid().hasSize(25).hasPerPage(25);
	}

	@Override
	protected LoomClientRequest<?> createRequest(LoomHttpClient client) {
		RoleCreateRequest request = new RoleCreateRequest();
		request.setName("perm-check");
		return client.createRole(request);
	}

	@Override
	protected LoomClientRequest<?> loadRequest(LoomHttpClient client) {
		return client.loadRole(ROLE_UUID);
	}

	@Override
	protected LoomClientRequest<?> listRequest(LoomHttpClient client) {
		return client.listRoles();
	}

	@Override
	protected LoomClientRequest<?> deleteRequest(LoomHttpClient client) {
		return client.deleteRole(ROLE_UUID);
	}
}
