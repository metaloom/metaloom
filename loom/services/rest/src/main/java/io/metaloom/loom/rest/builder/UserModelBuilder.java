package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.CUDElement;
import io.metaloom.loom.db.model.user.User;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.common.AbstractCreatorEditorRestResponse;
import io.metaloom.loom.rest.model.common.CreatorEditorStatus;
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
		setStatus(user, response);
		return response;
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
