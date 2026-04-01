package io.metaloom.loom.core.endpoint.test;

import static io.metaloom.loom.rest.model.assertj.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.metaloom.loom.api.filter.LoomFilterKey;
import io.metaloom.loom.api.sort.LoomSortKey;
import io.metaloom.loom.api.sort.SortDirection;
import io.metaloom.loom.client.common.LoomClientException;
import io.metaloom.loom.client.http.LoomHttpClient;
import io.metaloom.loom.core.endpoint.AbstractCRUDEndpointTest;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;

public class UserEndpointTest extends AbstractCRUDEndpointTest {

	@Test
	public void testBasics() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UserResponse response = client.loadUser(USER_UUID).sync();
			assertNotNull(response);

			for (int i = 0; i < 100; i++) {
				UserCreateRequest userRequest = new UserCreateRequest();
				userRequest.setUsername("user_" + i);
				client.createUser(userRequest).sync();
			}

			UserListResponse listResponse = client.listUsers().addLimit(12).sync();
			assertEquals(25, listResponse.getData().size(), "There should have been 25 users loaded");

			UUID uuid = listResponse.getData().get(0).getUuid();
			client.deleteUser(uuid).sync();
		}
	}

	@Test
	public void testFilterByUsername() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			for (int i = 0; i < 100; i++) {
				UserCreateRequest request = new UserCreateRequest();
				request.setUsername("user_" + i);
				client.createUser(request).sync();
			}

			UserListResponse pageResponse = client.listUsers()
				.addLimit(10)
				.addEquals(LoomFilterKey.USERNAME, "joedoe")
				.sync();
			assertEquals(1, pageResponse.getData().size(), "There should only be one result");
		}
	}

	@Test
	public void testSortByUsername() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			for (int i = 0; i < 100; i++) {
				UserCreateRequest request = new UserCreateRequest();
				request.setUsername("user_" + i);
				client.createUser(request).sync();
			}

			UserListResponse pageResponse = client.listUsers()
				.addLimit(10)
				.sortBy(LoomSortKey.UUID)
				.sortDirection(SortDirection.ASCENDING)
				.sync();

			for (UserResponse element : pageResponse.getData()) {
				System.out.println(element.getUsername());
			}
		}
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		UserResponse user = client.loadUser(USER_UUID).sync();
		assertThat(user).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		UserCreateRequest request = new UserCreateRequest();
		request.setUsername("dummy username");
		UserResponse user = client.createUser(request).sync();
		assertThat(user).isValid();

		UserResponse user2 = client.loadUser(user.getUuid()).sync();
		assertThat(user2).matches(user2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteUser(USER_UUID).sync();
		expect(404, "Not Found", client.loadUser(USER_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		UserUpdateRequest update = new UserUpdateRequest();
		update.setUsername("updated-username");
		UserResponse response = client.updateUser(USER_UUID, update).sync();
		assertThat(response).isValid();
	}

	@Test
	protected void testUpdateDeletedUser() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// 1. Delete user
			client.deleteUser(USER_UUID).sync();

			// 2. Assert that update fails
			UserUpdateRequest update = new UserUpdateRequest();
			update.setUsername("updated-username");
			// TODO encapsulate call in lambda to process errors with less code
			try {
				client.updateUser(USER_UUID, update).sync();
				fail("The request should have failed.");
			} catch (LoomClientException e) {
				assertEquals("Request failed {Not Found}", e.getMessage());
			}
		}
	}

	@Override
	protected void testReadPage(LoomHttpClient client) throws LoomClientException {
		for (int i = 0; i < 100; i++) {
			UserCreateRequest request = new UserCreateRequest();
			request.setUsername("user_" + i);
			client.createUser(request).sync();
		}

		UserListResponse pageResponse = client.listUsers().addLimit(10).sync();
		assertThat(pageResponse).isValid().hasSize(10).hasPerPage(10);

		UserListResponse secondPage = client.listUsers().addLimit(2).addFrom(pageResponse.getMetainfo().getLastUuid()).sync();
		assertEquals(2, secondPage.getMetainfo().getTotalCount(), "There should only be two users in the list");
		assertEquals(2, secondPage.getData().size(), "There should only be two responses");
	}

	@Test
	public void testReadPageWithDeletedUsers() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			for (int i = 0; i < 10; i++) {
				UserCreateRequest request = new UserCreateRequest();
				request.setUsername("user_" + i);
				client.createUser(request).sync();
			}

			for (int i = 0; i < 10; i++) {
				UserCreateRequest request = new UserCreateRequest();
				request.setUsername("deleted_user_" + i);
				UserResponse user = client.createUser(request).sync();
				client.deleteUser(user.getUuid()).sync();
			}

			UserListResponse pageResponse = client.listUsers().addLimit(100).sync();
			// Test fixture provides 2 users
			assertThat(pageResponse).isValid().hasSize(10 + 2).hasPerPage(100);
			for (UserResponse user : pageResponse.getData()) {
				assertFalse(user.getUsername().startsWith("deleted_user_"), "There should not be deleted users listed in the page response");
			}
			assertEquals(12, pageResponse.getMetainfo().getTotalCount(), "There should only be 10 users in the list");
			assertEquals(12, pageResponse.getData().size(), "There should only be 10 responses");
		}
	}

}
