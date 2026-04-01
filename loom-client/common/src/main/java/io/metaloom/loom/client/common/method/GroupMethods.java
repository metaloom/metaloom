package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.group.GroupCreateRequest;
import io.metaloom.loom.rest.model.group.GroupListResponse;
import io.metaloom.loom.rest.model.group.GroupResponse;
import io.metaloom.loom.rest.model.group.GroupUpdateRequest;

public interface GroupMethods {

	LoomClientRequest<GroupResponse> loadGroup(UUID groupUuid);

	LoomClientRequest<GroupResponse> createGroup(GroupCreateRequest request);

	LoomClientRequest<GroupResponse> updateGroup(UUID groupUuid, GroupUpdateRequest request);

	LoomClientRequest<GroupListResponse> listGroups();

	LoomClientRequest<NoResponse> deleteGroup(UUID groupUuid);
}
