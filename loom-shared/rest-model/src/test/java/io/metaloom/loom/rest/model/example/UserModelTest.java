package io.metaloom.loom.rest.model.example;

import static io.metaloom.loom.rest.model.example.ModelTestHelper.assertModel;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.metaloom.loom.rest.model.user.UserResponse;

public class UserModelTest extends AbstractModelTest {

	@Override
	public void testResponse() {
		assertModel(userResponse(), "UserResponse");
		UserResponse response = load("user/user-response", UserResponse.class);
		assertNotNull(response);
	}

	@Override
	public void testCreateRequest() {
		assertModel(userCreateRequest(), "UserCreateRequest");
	}

	@Override
	public void testUpdateRequest() {
		assertModel(userUpdateRequest(), "UserUpdateRequest");
	}

	@Override
	public void testReference() {
		assertModel(userReference(), "UserReference");
	}

	@Override
	public void testListResponse() {
		assertModel(userListResponse(), "UserListResponse");
	}
}
