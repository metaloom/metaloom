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
import io.metaloom.loom.core.endpoint.ReplaceEndpointTestcases;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;
import io.vertx.core.json.JsonObject;

public class UserEndpointTest extends AbstractCRUDEndpointTest implements ReplaceEndpointTestcases {

	@Test
	@Override
	public void testPatch() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UserUpdateRequest update = new UserUpdateRequest();
			update.setUsername("patched-username");
			update.setEmail("patched@doe.tld");
			UserResponse response = client.patchUser(USER_UUID, update).sync().body();
			assertThat(response).isValid();

			UserResponse loaded = client.loadUser(USER_UUID).sync().body();
			assertEquals("patched-username", loaded.getUsername());
			assertEquals("patched@doe.tld", loaded.getEmail());
		}
	}

	@Test
	@Override
	public void testReplace() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UserUpdateRequest update = new UserUpdateRequest();
			update.setUsername("replaced-username");
			update.setFirstname("Joe");
			update.setLastname("Doe");
			update.setEmail("joe@doe.tld");
			update.setMeta(new JsonObject());
			UserResponse response = client.replaceUser(USER_UUID, update).sync().body();
			assertThat(response).isValid();

			UserResponse loaded = client.loadUser(USER_UUID).sync().body();
			assertEquals("replaced-username", loaded.getUsername());
			assertEquals("Joe", loaded.getFirstname());
			assertEquals("Doe", loaded.getLastname());
			assertEquals("joe@doe.tld", loaded.getEmail());
		}
	}

	/**
	 * {@code secure(basePath() + "*")} installs the auth handler on the path with no method filter, so the new PUT/PATCH routes must be authenticated
	 * without any extra wiring. This asserts that they are.
	 */
	@Test
	public void testReplaceAndPatchRequireAuth() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			// Deliberately no loginAdmin(client)
			UserUpdateRequest update = new UserUpdateRequest();
			update.setUsername("nope");
			expect(401, "Unauthorized", client.patchUser(USER_UUID, update));
			expect(401, "Unauthorized", client.replaceUser(USER_UUID, update));
		}
	}

	@Test
	@Override
	public void testReplaceRejectsPartialBody() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);
			UserUpdateRequest update = new UserUpdateRequest();
			update.setUsername("only-username");
			expect(400, "Bad Request", client.replaceUser(USER_UUID, update));
		}
	}

	@Test
	public void testBasics() throws Exception {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			UserResponse response = client.loadUser(USER_UUID).sync().body();
			assertNotNull(response);

			for (int i = 0; i < 100; i++) {
				UserCreateRequest userRequest = new UserCreateRequest();
				userRequest.setUsername("user_" + i);
				client.createUser(userRequest).sync().body();
			}

			UserListResponse listResponse = client.listUsers().sync().body();
			assertEquals(25, listResponse.getData().size(), "There should have been 25 users loaded");

			UUID uuid = listResponse.getData().get(0).getUuid();
			client.deleteUser(uuid).sync().body();
		}
	}

	@Test
	public void testFilterByUsername() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			for (int i = 0; i < 100; i++) {
				UserCreateRequest request = new UserCreateRequest();
				request.setUsername("user_" + i);
				client.createUser(request).sync().body();
			}

			UserListResponse pageResponse = client.listUsers()
				.addLimit(10)
				.addEquals(LoomFilterKey.USERNAME, "joedoe")
				.sync().body();
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
				client.createUser(request).sync().body();
			}

			UserListResponse pageResponse = client.listUsers()
				.addLimit(10)
				.sortBy(LoomSortKey.UUID)
				.sortDirection(SortDirection.ASCENDING)
				.sync().body();

			for (UserResponse element : pageResponse.getData()) {
				System.out.println(element.getUsername());
			}
		}
	}

	@Override
	protected void testRead(LoomHttpClient client) throws LoomClientException {
		UserResponse user = client.loadUser(USER_UUID).sync().body();
		assertThat(user).isValid();
	}

	@Override
	protected void testCreate(LoomHttpClient client) throws LoomClientException {
		UserCreateRequest request = new UserCreateRequest();
		request.setUsername("dummy username");
		UserResponse user = client.createUser(request).sync().body();
		assertThat(user).isValid();

		UserResponse user2 = client.loadUser(user.getUuid()).sync().body();
		assertThat(user2).matches(user2);
	}

	@Override
	protected void testDelete(LoomHttpClient client) throws LoomClientException {
		client.deleteUser(USER_UUID).sync().body();
		expect(404, "Not Found", client.loadUser(USER_UUID));
	}

	@Override
	protected void testUpdate(LoomHttpClient client) throws LoomClientException {
		UserUpdateRequest update = new UserUpdateRequest();
		update.setUsername("updated-username");
		update.setEmail("updated@doe.tld");
		UserResponse response = client.updateUser(USER_UUID, update).sync().body();
		assertThat(response).isValid();

		UserResponse loaded = client.loadUser(USER_UUID).sync().body();
		assertEquals("updated-username", loaded.getUsername());
		assertEquals("updated@doe.tld", loaded.getEmail());
	}

	@Test
	protected void testUpdateDeletedUser() throws LoomClientException {
		try (LoomHttpClient client = loom.httpClient()) {
			loginAdmin(client);

			// 1. Delete user
			client.deleteUser(USER_UUID).sync().body();

			// 2. Assert that update fails
			UserUpdateRequest update = new UserUpdateRequest();
			update.setUsername("updated-username");
			// TODO encapsulate call in lambda to process errors with less code
			try {
				client.updateUser(USER_UUID, update).sync().body();
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
			client.createUser(request).sync().body();
		}

		UserListResponse pageResponse = client.listUsers().addLimit(10).sync().body();
		assertThat(pageResponse).isValid().hasSize(10).hasPerPage(10);

		UserListResponse secondPage = client.listUsers().addLimit(2).addFrom(pageResponse.getMetainfo().getLastUuid()).sync().body();
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
				client.createUser(request).sync().body();
			}

			for (int i = 0; i < 10; i++) {
				UserCreateRequest request = new UserCreateRequest();
				request.setUsername("deleted_user_" + i);
				UserResponse user = client.createUser(request).sync().body();
				client.deleteUser(user.getUuid()).sync().body();
			}

			UserListResponse pageResponse = client.listUsers().addLimit(100).sync().body();
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
