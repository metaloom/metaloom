package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;

public interface UserMethods {

	LoomClientRequest<UserResponse> loadUser(UUID userUuid);

	LoomClientRequest<UserResponse> createUser(UserCreateRequest request);

	LoomClientRequest<UserResponse> updateUser(UUID userUuid, UserUpdateRequest request);

	LoomClientRequest<UserListResponse> listUsers();

	LoomClientRequest<NoResponse> deleteUser(UUID userUuid);
}
