package io.metaloom.loom.client.common.method;

import java.util.UUID;

import io.metaloom.loom.client.common.LoomClientRequest;
import io.metaloom.loom.rest.model.NoResponse;
import io.metaloom.loom.rest.model.space.SpaceCreateRequest;
import io.metaloom.loom.rest.model.space.SpaceListResponse;
import io.metaloom.loom.rest.model.space.SpaceResponse;
import io.metaloom.loom.rest.model.space.SpaceUpdateRequest;

public interface SpaceMethods {

	LoomClientRequest<SpaceResponse> loadSpace(UUID uuid);

	LoomClientRequest<SpaceResponse> createSpace(SpaceCreateRequest request);

	LoomClientRequest<SpaceResponse> updateSpace(UUID uuid, SpaceUpdateRequest request);

	LoomClientRequest<SpaceListResponse> listSpaces();

	LoomClientRequest<NoResponse> deleteSpace(UUID uuid);
}
