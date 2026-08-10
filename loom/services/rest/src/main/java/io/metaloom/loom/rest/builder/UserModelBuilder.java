package io.metaloom.loom.rest.builder;

import java.util.UUID;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.model.attachment.Attachment;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.RESTConstants;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.metaloom.loom.rest.model.common.CreatorEditorStatus;
import io.metaloom.loom.rest.model.user.UserAvatarResponse;
import io.metaloom.loom.rest.model.user.UserListResponse;
import io.metaloom.loom.rest.model.user.UserReference;
import io.metaloom.loom.rest.model.user.UserResponse;

public interface UserModelBuilder extends ModelBuilder {

	default UserResponse toResponse(User user) {
		UserResponse response = new UserResponse();
		response.setUsername(user.getUsername());
		response.setFirstname(user.getFirstname());
		response.setLastname(user.getLastname());
		response.setEmail(user.getEmail());
		response.setUuid(user.getUuid());
		// A URL rather than a uuid: how an account's picture is addressed is this layer's business, not the caller's. Both
		// uuids the URL needs are on the user row, so this stays a pure function of what was already loaded.
		response.setAvatarUrl(userAvatarUrl(user.getUuid(), user.getAvatarAttachmentUuid()));
		setStatus(user, response);
		return response;
	}

	default UserAvatarResponse toUserAvatarResponse(User user, Attachment avatar) {
		UserAvatarResponse response = new UserAvatarResponse();
		response.setUuid(avatar.getUuid());
		response.setFilename(avatar.getFilename());
		response.setMimeType(avatar.getMimeType());
		response.setSize(avatar.getSize());
		response.setUrl(userAvatarUrl(user.getUuid(), avatar.getUuid()));
		setStatus(avatar, response);
		return response;
	}

	/**
	 * Where an account's picture is served from, or null when there is none.
	 *
	 * <p>
	 * Always the {@code /users/:uuid/avatar/data} form, never {@code /me/avatar/data}, even when the caller is reading their own record. The URL ends
	 * up in an {@code <img src>} that other users' browsers also load - a comment author's face beside their comment - and a self-relative URL there
	 * would show every reader their own picture.
	 * </p>
	 */
	private static String userAvatarUrl(UUID userUuid, UUID avatarUuid) {
		if (userUuid == null || avatarUuid == null) {
			return null;
		}
		return RESTConstants.API_V1_PATH + "/users/" + userUuid + "/avatar/data";
	}

	default UserListResponse toUserList(Page<User> page) {
		return setPage(new UserListResponse(), page, this::toResponse);
	}

	default UserReference toReference(User user) {
		UserReference ref = new UserReference();
		ref.setUuid(user.getUuid());
		ref.setName(user.getUsername());
		return ref;
	}

	default void setStatus(CUDElement<?> element, AbstractCreatorEditorRestResponse<?> response) {
		// Creator and editor are absent on rows written by a Cortex worker rather than a user
		// (asset components, detections, the processing ledger). Leave the reference unset
		// instead of failing to build the response.
		User creator = element.getCreatorUuid() == null ? null : daos().userDao().load(element.getCreatorUuid());
		User editor = element.getEditorUuid() == null ? null : daos().userDao().load(element.getEditorUuid());
		CreatorEditorStatus status = response.getStatus();
		status.setCreated(element.getCreated());
		status.setEdited(element.getEdited());
		status.setCreator(creator == null ? null : toReference(creator));
		status.setEditor(editor == null ? null : toReference(editor));
	}

}
