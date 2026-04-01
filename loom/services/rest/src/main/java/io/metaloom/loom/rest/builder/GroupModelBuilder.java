package io.metaloom.loom.rest.builder;

import io.metaloom.loom.db.model.group.Group;
import io.metaloom.loom.db.page.Page;
import io.metaloom.loom.rest.model.group.GroupListResponse;
import io.metaloom.loom.rest.model.group.GroupResponse;

public interface GroupModelBuilder extends ModelBuilder, UserModelBuilder {

	default GroupResponse toResponse(Group group) {
		GroupResponse response = new GroupResponse();
		response.setUuid(group.getUuid());
		response.setName(group.getName());
		response.setMeta(group.getMeta());
		setStatus(group, response);
		return response;
	}

	default GroupListResponse toGroupList(Page<Group> page) {
		return setPage(new GroupListResponse(), page, this::toResponse);
	}

}
