package io.metaloom.loom.client.common.method;

import java.io.File;
import java.util.UUID;

import io.metaloom.loom.client.common.LoomBinaryResponse;
import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.user.UserAvatarResponse;
import io.metaloom.loom.rest.model.user.UserCreateRequest;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserResponse;
import io.metaloom.loom.rest.model.user.UserUpdateRequest;

public interface UserMethods {

	LoomClientRequest<UserResponse> loadUser(UUID userUuid);

	LoomClientRequest<UserResponse> createUser(UserCreateRequest request);

	LoomClientRequest<UserResponse> updateUser(UUID userUuid, UserUpdateRequest request);

	LoomClientRequest<UserResponse> patchUser(UUID userUuid, UserUpdateRequest request);

	LoomClientRequest<UserResponse> replaceUser(UUID userUuid, UserUpdateRequest request);

	LoomClientRequest<UserListResponse> listUsers();

	LoomClientRequest<NoResponse> deleteUser(UUID userUuid);

	// --- The account picture ---------------------------------------------------------------------
	//
	// Two sets: the /users/:uuid form for administrators, and the /me form the profile screen uses.
	// The second exists because changing your own picture must not require UPDATE_USER, which is the
	// permission to edit anybody's account and which no ordinary user holds.

	LoomClientRequest<UserAvatarResponse> loadUserAvatar(UUID userUuid);

	/**
	 * Upload a user's avatar picture, replacing any previous one.
	 *
	 * @param poolUuid target storage pool, or null for the deployment's default storage
	 */
	LoomClientRequest<UserAvatarResponse> uploadUserAvatar(UUID userUuid, File file, String mimeType, UUID poolUuid);

	LoomClientRequest<LoomBinaryResponse> downloadUserAvatar(UUID userUuid);

	LoomClientRequest<NoResponse> deleteUserAvatar(UUID userUuid);

	LoomClientRequest<UserAvatarResponse> loadMyAvatar();

	LoomClientRequest<UserAvatarResponse> uploadMyAvatar(File file, String mimeType, UUID poolUuid);

	LoomClientRequest<LoomBinaryResponse> downloadMyAvatar();

	LoomClientRequest<NoResponse> deleteMyAvatar();
}
